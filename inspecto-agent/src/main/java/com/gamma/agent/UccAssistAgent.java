package com.gamma.agent;

import com.gamma.agent.diagnose.DiagnosisStore;
import com.gamma.agent.diagnose.FailureReactor;
import com.gamma.agent.diagnose.ModelDiagnoser;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.agent.CapabilityRegistry;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.observe.AgentCompleted;
import com.gamma.agent.kernel.observe.AuditSink;
import com.gamma.agent.kernel.orchestrate.SyncOrchestrator;
import com.gamma.agent.model.AssistModelSettings;
import com.gamma.agent.model.DelegatingModelRouter;
import com.gamma.agent.model.ModelProviderFactory;
import com.gamma.agent.model.ProviderSettings;
import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.reason.ConfidenceEstimator;
import com.gamma.agent.kernel.reason.EscalationPolicy;
import com.gamma.agent.kernel.reason.EscalationRung;
import com.gamma.agent.kernel.retrieve.DocRetriever;
import com.gamma.agent.skill.UccAgentContext;
import com.gamma.agent.skill.DiagnoseAndAlertSkill;
import com.gamma.agent.skill.ExplainEntitySkill;
import com.gamma.agent.skill.KpiToSqlSkill;
import com.gamma.agent.skill.NlToScheduleSkill;
import com.gamma.agent.skill.ReportNarrativeSkill;
import com.gamma.agent.skill.ReportSqlSkill;
import com.gamma.agent.skill.SuggestConfigSkill;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.Diagnosis;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.service.CollectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The real embedded assist agent (v3.3.0, M3) — the {@link AssistAgent} the optional
 * {@code file-processor-agent} module contributes via {@code ServiceLoader}. It captures the host
 * service's read-only handles in {@link #init} (into a {@link UccAgentContext}), builds the
 * {@link CapabilityRegistry}, and dispatches {@code POST /assist/{intent}} calls through the shared
 * {@link SyncOrchestrator} (R1): it resolves the matching
 * {@link com.gamma.agent.kernel.agent.Capability}, runs it under an {@link EscalationPolicy} — attempt →
 * {@link UccConfidenceEstimator estimate confidence} → surface if it clears the capability's
 * threshold, else {@link EscalationRung.Abstain abstain} (UNAVAILABLE) rather than ship a
 * low-confidence guess — and emits one keys-only {@link AgentCompleted} to the {@link AuditSink}
 * (ADR-0008). UCC keeps only the transport concerns: mapping the kernel {@link AgentResult} back onto
 * the lean-core wire {@link AssistResult} in {@link #toWire} (the numeric confidence rides along), and
 * the {@link LoggingAuditSink} that preserves the {@code [ASSIST]} operator log.
 *
 * <h3>Lazy & abstain-safe</h3>
 * Construction and {@link #init} touch no network: the {@link ModelRouter}'s providers build their
 * model clients lazily and report unavailable until a deployment turns the assist layer on. So
 * discovering this agent in a CI/test process is harmless — capabilities simply return "model
 * unavailable" until Ollama is configured.
 *
 * <p>The agent holds no write credential and performs no autonomous action — every capability is
 * read-only or draft-only ({@code applyVia} is always {@code null}).
 */
public final class UccAssistAgent implements AssistAgent {

    private static final Logger log = LoggerFactory.getLogger(UccAssistAgent.class);

    private final ModelRouter router;
    private final AuditSink audit;
    /** UCC's posture: estimate confidence, and {@link EscalationRung.Abstain abstain} below threshold
        rather than ship a low-confidence guess (no tier-bump / human-handoff rungs — single-tier app). */
    private final EscalationPolicy escalation =
            new EscalationPolicy(List.of(new EscalationRung.Abstain()));
    private final ConfidenceEstimator estimator = new UccConfidenceEstimator();
    private volatile CapabilityRegistry registry;
    private volatile SyncOrchestrator orchestrator;
    private volatile UccAgentContext context;
    private volatile DiagnosisStore diagnoses;
    private volatile FailureReactor reactor;
    private volatile AssistMetrics metrics;

    /**
     * {@code ServiceLoader} entry point (abstain-safe): the router is a hot-swappable delegate over
     * the persisted {@code assist-settings} provider (v4.1), falling back to the legacy
     * environment-resolved Ollama wiring when no settings file exists. Live reconfiguration via
     * {@code POST /assist/settings} swaps the delegate; skills re-route on their next call.
     */
    public UccAssistAgent() {
        this(new DelegatingModelRouter(ModelProviderFactory.fromPersisted()));
    }

    /** Test/embedder entry point: inject a router (e.g. a fake-backed one) for deterministic runs. */
    public UccAssistAgent(ModelRouter router) {
        this(router, null);
    }

    /** Inject a router and an {@link AuditSink} (e.g. a capturing list in tests). {@code null} ⇒ log-only. */
    public UccAssistAgent(ModelRouter router, AuditSink audit) {
        this.router = router;
        this.audit = (audit == null) ? AuditSink.NONE : audit;
    }

    @Override
    public String name() {
        return "ucc-assist";
    }

    @Override
    public void init(CollectorService service) {
        DocRetriever docs = DocRetriever.fromDir(docsDir());
        // The orchestrator emits the per-call AgentCompleted via ctx.audit(); wrap the injected sink so
        // the familiar [ASSIST] operator log is preserved, then delegate to the embedder's sink.
        // Audit chain: operator log line → per-intent counters (B2) → the embedder's sink.
        this.metrics = new AssistMetrics(audit);
        this.context = new UccAgentContext(service.catalog(), service.reports(),
                service.statusStore(), docs, router, service.configSource(), new LoggingAuditSink(metrics));
        this.registry = CapabilityRegistry.of(List.of(
                new ExplainEntitySkill(),
                new NlToScheduleSkill(),
                new SuggestConfigSkill(),
                new KpiToSqlSkill(),
                new DiagnoseAndAlertSkill(),
                new ReportSqlSkill(),
                new ReportNarrativeSkill()));
        // The shared sync pipeline (R1): resolve → escalate (estimate confidence, abstain below
        // threshold) → audit one AgentCompleted. UCC supplies the registry, estimator, and abstain policy.
        this.orchestrator = new SyncOrchestrator(registry, estimator, escalation);

        // ── M7: event-driven failure diagnosis. Subscribe BEFORE start() (the SPI contract) so the
        // reactor sees the first FAILED batch. The reactor hands work to its own executor, so the
        // ingest thread is never blocked; with no model it still records a deterministic heuristic. ──
        this.diagnoses = new DiagnosisStore();
        this.reactor = new FailureReactor(
                new ModelDiagnoser(router, service.catalog()), diagnoses, this::auditDiagnosis,
                com.gamma.agent.model.AssistTunables.reactorQueueCapacity(FailureReactor.DEFAULT_QUEUE_CAPACITY));
        service.eventBus().subscribe(reactor::onEvent);

        log.info("Assist agent '{}' initialised: skills={}, docs={}, modelAvailable={}, failureReactor=on",
                name(), registry.ids(), docs.size(), router.anyAvailable());
    }

    @Override
    public AssistResult assist(AssistRequest request) {
        SyncOrchestrator o = orchestrator;
        if (o == null) return AssistResult.unsupported(request.intent());
        try {
            AgentRequest agentReq = new AgentRequest(request.intent(), request.screenContext(),
                    request.partialInput(), request.userText());
            // The shared orchestrator resolves the capability (unknown intent → UNSUPPORTED, still
            // audited), runs it through the abstain-gated escalation policy, and emits one AgentCompleted.
            // UCC keeps only the transport concerns: map the neutral result onto the wire type and
            // contain unexpected failures.
            AgentResult ar = o.run(agentReq, context);
            return toWire(ar);
        } catch (RuntimeException e) {
            log.warn("assist intent '{}' failed", request.intent(), e);
            return AssistResult.unavailable(request.intent(), "assist error: " + e.getMessage());
        }
    }

    /** Map a kernel {@link AgentResult} onto the lean-core wire {@link AssistResult} (the U1.5 adapter). */
    private static AssistResult toWire(AgentResult ar) {
        AssistResult.Status status = AssistResult.Status.valueOf(ar.status().name());
        List<AssistResult.Citation> citations = ar.evidence().stream()
                .map(e -> new AssistResult.Citation(e.effectiveTierLabel(), e.sourceRef()))
                .toList();
        // The wire confidence is now the kernel's numeric, estimator-computed value (4.0 reshape).
        return new AssistResult(ar.capabilityId(), status, ar.answer(), citations, ar.links(),
                ar.rationale(), ar.confidence(), ar.validated(), ar.applyVia(), ar.message(), ar.data());
    }

    /**
     * Resolve the docs-RAG corpus directory from {@code -Dassist.docs.dir} (default {@code docs/} under
     * the working dir). The kernel {@link DocRetriever} stays config-neutral, so this UCC-facing knob is
     * resolved here and handed to {@link DocRetriever#fromDir}.
     */
    private static Path docsDir() {
        String d = System.getProperty("assist.docs.dir");
        return Path.of(d == null || d.isBlank() ? "docs" : d);
    }

    /**
     * One {@link AgentCompleted} per event-driven diagnosis (M7), mirroring the per-call trail. Records
     * context <em>keys</em> only ({@code batchId}/{@code pipeline}/{@code severity}), never data-plane
     * values. Runs on the reactor's executor, not the ingest thread.
     */
    private void auditDiagnosis(Diagnosis d) {
        AgentCompleted event = new AgentCompleted(DiagnoseAndAlertSkill.ID, System.currentTimeMillis(),
                AgentResult.Status.OK, d.citations().size(), 0L,
                Set.of("batchId", "pipeline", "severity"), null, !d.heuristicOnly(), 0, 0.0, 0, 0);
        log.info("[ASSIST] diagnosis intent={} batch={} pipeline={} severity={} heuristicOnly={} citations={}",
                event.capabilityId(), d.batchId(), d.pipeline(), d.severity(), d.heuristicOnly(),
                event.evidenceCount());
        audit.emit(event);
    }

    /**
     * The masked settings view backing {@code GET /assist/settings} (v4.1): current provider config
     * (key <b>presence</b> only — never the key), per-provider defaults for the UI to seed forms,
     * and the provider ids selectable on this classpath.
     */
    @Override
    public java.util.Map<String, Object> settings() {
        ProviderSettings s = AssistModelSettings.load().orElseGet(() -> ProviderSettings.defaults("ollama"));
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("supported", true);
        out.put("provider", s.provider());
        out.put("baseUrl", s.baseUrl());
        out.put("apiKeyRef", s.apiKeyRef());
        out.put("apiKeySet", AssistModelSettings.resolveApiKey(s) != null);
        out.put("models", modelMap(s.models()));
        out.put("timeoutSeconds", s.timeoutSeconds());
        out.put("availableProviders", ModelProviderFactory.availableProviders());
        out.put("knownProviders", ProviderSettings.knownProviders());
        out.put("modelAvailable", router.anyAvailable());
        java.util.Map<String, Object> defaults = new java.util.LinkedHashMap<>();
        for (String p : ProviderSettings.knownProviders()) {
            ProviderSettings d = ProviderSettings.defaults(p);
            defaults.put(p, java.util.Map.of(
                    "baseUrl", d.baseUrl() == null ? "" : d.baseUrl(),
                    "apiKeyRef", d.apiKeyRef() == null ? "" : d.apiKeyRef(),
                    "models", modelMap(d.models()),
                    "local", d.local()));
        }
        out.put("defaults", defaults);
        return out;
    }

    /**
     * Apply new provider settings (v4.1, {@code POST /assist/settings}, scope {@code assist.write}):
     * validate → persist (never the key — a submitted {@code apiKey} goes to the in-memory session
     * store only) → hot-swap the router delegate. Returns the fresh masked view.
     *
     * @throws IllegalArgumentException on an unknown provider or a provider whose backing module is
     *         absent (mapped to HTTP 400 by the control plane)
     */
    @Override
    public java.util.Map<String, Object> updateSettings(java.util.Map<String, Object> body) {
        String provider = str(body.get("provider"));
        if (provider == null) throw new IllegalArgumentException("'provider' is required");
        provider = provider.trim().toLowerCase(Locale.ROOT);
        if (!ProviderSettings.knownProviders().contains(provider))
            throw new IllegalArgumentException("unknown provider '" + provider + "'; known: "
                    + ProviderSettings.knownProviders());
        if (!ModelProviderFactory.availableProviders().contains(provider))
            throw new IllegalArgumentException("provider '" + provider + "' requires the "
                    + "file-processor-agent-hosted jar on the classpath");

        ProviderSettings defaults = ProviderSettings.defaults(provider);
        java.util.EnumMap<ModelTier, String> models = new java.util.EnumMap<>(ModelTier.class);
        Object modelsBody = body.get("models");
        for (ModelTier t : ModelTier.values()) {
            String fromBody = (modelsBody instanceof java.util.Map<?, ?> m)
                    ? str(m.get(t.name().toLowerCase(Locale.ROOT))) : null;
            String v = fromBody != null ? fromBody : defaults.model(t);
            if (v != null && !v.isBlank()) models.put(t, v.trim());
        }
        String baseUrl = str(body.get("baseUrl"));
        String apiKeyRef = str(body.get("apiKeyRef"));
        int timeout = (body.get("timeoutSeconds") instanceof Number n)
                ? n.intValue() : defaults.timeoutSeconds();
        ProviderSettings s = new ProviderSettings(provider,
                baseUrl != null ? baseUrl : defaults.baseUrl(),
                apiKeyRef != null ? apiKeyRef : defaults.apiKeyRef(),
                models, timeout);

        // A raw key rides the request once, lands in the in-memory session store, and is never
        // persisted or echoed. Restarts resolve the key from the env var named by apiKeyRef.
        String apiKey = str(body.get("apiKey"));
        if (apiKey != null) AssistModelSettings.setSessionKey(provider, apiKey);

        AssistModelSettings.save(s);
        if (router instanceof DelegatingModelRouter d) {
            d.set(ModelProviderFactory.create(s));
        }
        log.info("[ASSIST] settings updated: provider={} models={} keySet={} (file={})", provider,
                modelMap(s.models()), AssistModelSettings.resolveApiKey(s) != null,
                AssistModelSettings.path());
        return settings();
    }

    /**
     * Round-trip each tier's provider with a one-word prompt (v4.1,
     * {@code POST /assist/settings/test}): per-tier {@code ok}/{@code latencyMs}/{@code error} so the
     * settings screen can verify a configuration before relying on it.
     */
    @Override
    public java.util.Map<String, Object> testSettings() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("supported", true);
        for (ModelTier t : ModelTier.values()) {
            java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
            ModelProvider p = router.providerFor(t);
            r.put("provider", p.name());
            if (!p.available()) {
                r.put("ok", false);
                r.put("error", "not configured (provider unavailable)");
            } else {
                long start = System.nanoTime();
                try {
                    p.generate(ModelRequest.text(t, null, "Reply with the single word: OK"));
                    r.put("ok", true);
                    r.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
                } catch (RuntimeException e) {
                    r.put("ok", false);
                    r.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
                    r.put("error", e.getMessage());
                }
            }
            out.put(t.name().toLowerCase(Locale.ROOT), r);
        }
        return out;
    }

    private static java.util.Map<String, String> modelMap(java.util.Map<ModelTier, String> models) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (ModelTier t : ModelTier.values()) {
            String v = models.get(t);
            if (v != null) m.put(t.name().toLowerCase(Locale.ROOT), v);
        }
        return m;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Per-intent call/ok/unavailable/repaired counters (v4.1, B2) — the operator's surface for tuning
     * the B1 knobs (confidence threshold, repair rounds) against real traffic. Backs
     * {@code GET /assist/metrics}; counts only, never data-plane values.
     */
    @Override
    public java.util.Map<String, Object> metrics() {
        AssistMetrics m = metrics;
        if (m == null) return java.util.Map.of("supported", false);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("supported", true);
        out.put("intents", m.snapshot());
        return out;
    }

    /** The agent's recent failure diagnoses (M7), newest first — backs {@code GET /assist/diagnoses}. */
    @Override
    public List<Diagnosis> recentDiagnoses(int limit) {
        DiagnosisStore d = diagnoses;
        return d == null ? List.of() : d.recent(limit);
    }

    /** Release the failure reactor's executor on service shutdown. */
    @Override
    public void close() {
        FailureReactor r = reactor;
        if (r != null) r.close();
    }
}
