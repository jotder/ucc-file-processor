package com.gamma.agent;

import com.gamma.agent.diagnose.DiagnosisStore;
import com.gamma.agent.diagnose.FailureReactor;
import com.gamma.agent.diagnose.ModelDiagnoser;
import com.gamma.agentkernel.agent.AgentRequest;
import com.gamma.agentkernel.agent.AgentResult;
import com.gamma.agentkernel.agent.CapabilityRegistry;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.agentkernel.observe.AgentCompleted;
import com.gamma.agentkernel.observe.AuditSink;
import com.gamma.agentkernel.orchestrate.SyncOrchestrator;
import com.gamma.agentkernel.provider.ollama.OllamaModelProvider;
import com.gamma.agentkernel.reason.ConfidenceEstimator;
import com.gamma.agentkernel.reason.EscalationPolicy;
import com.gamma.agentkernel.reason.EscalationRung;
import com.gamma.agentkernel.retrieve.DocRetriever;
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
import com.gamma.service.SourceService;
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
 * {@link com.gamma.agentkernel.agent.Capability}, runs it under an {@link EscalationPolicy} — attempt →
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

    /** {@code ServiceLoader} entry point: model router resolved from the environment (abstain-safe). */
    public UccAssistAgent() {
        this(OllamaModelProvider.fromEnvironment());
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
    public void init(SourceService service) {
        DocRetriever docs = DocRetriever.fromDir(docsDir());
        // The orchestrator emits the per-call AgentCompleted via ctx.audit(); wrap the injected sink so
        // the familiar [ASSIST] operator log is preserved, then delegate to the embedder's sink.
        this.context = new UccAgentContext(service.catalog(), service.reports(),
                service.statusStore(), docs, router, service.configSource(), new LoggingAuditSink(audit));
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
                new ModelDiagnoser(router, service.catalog()), diagnoses, this::auditDiagnosis);
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
