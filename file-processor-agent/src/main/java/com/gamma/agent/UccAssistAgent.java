package com.gamma.agent;

import com.gamma.agent.diagnose.DiagnosisStore;
import com.gamma.agent.diagnose.FailureReactor;
import com.gamma.agent.diagnose.ModelDiagnoser;
import com.gamma.agentkernel.agent.AgentRequest;
import com.gamma.agentkernel.agent.AgentResult;
import com.gamma.agentkernel.agent.CapabilityRegistry;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.agentkernel.provider.ollama.OllamaModelProvider;
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
import java.util.Set;
import java.util.function.Consumer;

/**
 * The real embedded assist agent (v3.3.0, M3) — the {@link AssistAgent} the optional
 * {@code file-processor-agent} module contributes via {@code ServiceLoader}. It captures the host
 * service's read-only handles in {@link #init} (into a {@link UccAgentContext}), builds the
 * {@link CapabilityRegistry}, and dispatches {@code POST /assist/{intent}} calls to the matching
 * {@link com.gamma.agentkernel.agent.Capability}, mapping the kernel {@link AgentResult} back onto the
 * lean-core wire {@link AssistResult} in {@link #toWire}.
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
    private final Consumer<AuditEvent> audit;
    private volatile CapabilityRegistry registry;
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

    /** Inject a router and an audit sink (e.g. a capturing list in tests). A {@code null} sink logs only. */
    public UccAssistAgent(ModelRouter router, Consumer<AuditEvent> audit) {
        this.router = router;
        this.audit = audit;
    }

    @Override
    public String name() {
        return "ucc-assist";
    }

    @Override
    public void init(SourceService service) {
        DocRetriever docs = DocRetriever.fromDir(docsDir());
        this.context = new UccAgentContext(service.catalog(), service.reports(),
                service.statusStore(), docs, router, service.configSource());
        this.registry = CapabilityRegistry.of(List.of(
                new ExplainEntitySkill(),
                new NlToScheduleSkill(),
                new SuggestConfigSkill(),
                new KpiToSqlSkill(),
                new DiagnoseAndAlertSkill(),
                new ReportSqlSkill(),
                new ReportNarrativeSkill()));

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
        CapabilityRegistry r = registry;
        if (r == null) return AssistResult.unsupported(request.intent());
        long t0 = System.nanoTime();
        try {
            AgentRequest agentReq = new AgentRequest(request.intent(), request.screenContext(),
                    request.partialInput(), request.userText());
            AssistResult result = toWire(r.dispatch(agentReq, context));
            audit(request, result, t0);
            return result;
        } catch (RuntimeException e) {
            log.warn("assist intent '{}' failed", request.intent(), e);
            return AssistResult.unavailable(request.intent(), "assist error: " + e.getMessage());
        }
    }

    /** Map a kernel {@link AgentResult} onto the lean-core wire {@link AssistResult} (U1.4 adapter). */
    private static AssistResult toWire(AgentResult ar) {
        AssistResult.Status status = AssistResult.Status.valueOf(ar.status().name());
        List<AssistResult.Citation> citations = ar.evidence().stream()
                .map(e -> new AssistResult.Citation(e.effectiveTierLabel(), e.sourceRef()))
                .toList();
        // The wire confidence stays a String this milestone (U1.5 reshapes it to a double).
        String confidence = (status == AssistResult.Status.OK) ? "local" : null;
        return new AssistResult(ar.capabilityId(), status, ar.answer(), citations, ar.links(),
                ar.rationale(), confidence, ar.validated(), ar.applyVia(), ar.message(), ar.data());
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

    /** One audit event per call — agent-<em>suggested</em>, never applied (read-only in M3). */
    private void audit(AssistRequest req, AssistResult res, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        AuditEvent event = new AuditEvent(req.intent(), res.status(),
                res.citations().size(), ms, req.screenContext().keySet());
        log.info("[ASSIST] intent={} status={} citations={} ms={} ctxKeys={}",
                event.intent(), event.status(), event.citationCount(), event.durationMs(), event.contextKeys());
        if (audit != null) audit.accept(event);
    }

    /**
     * One audit event per event-driven diagnosis (M7), mirroring the per-call trail. Records context
     * <em>keys</em> only ({@code batchId}/{@code pipeline}/{@code severity}), never data-plane values.
     * Runs on the reactor's executor, not the ingest thread.
     */
    private void auditDiagnosis(Diagnosis d) {
        AuditEvent event = new AuditEvent(DiagnoseAndAlertSkill.ID, AssistResult.Status.OK,
                d.citations().size(), 0L, Set.of("batchId", "pipeline", "severity"));
        log.info("[ASSIST] diagnosis intent={} batch={} pipeline={} severity={} heuristicOnly={} citations={}",
                event.intent(), d.batchId(), d.pipeline(), d.severity(), d.heuristicOnly(), event.citationCount());
        if (audit != null) audit.accept(event);
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
