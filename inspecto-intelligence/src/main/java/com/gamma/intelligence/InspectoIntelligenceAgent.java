package com.gamma.intelligence;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.Citation;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.EoiAgentException;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.InlineArtifact;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.AnswerSink;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.intelligence.action.AgentApprovals;
import com.gamma.intelligence.action.ApprovalStore;
import com.gamma.intelligence.action.ComponentActions;
import com.gamma.intelligence.action.OperationalActions;
import com.gamma.intelligence.action.RunbookActions;
import com.gamma.intelligence.context.ContextBroker;
import com.gamma.intelligence.investigation.Case;
import com.gamma.intelligence.investigation.CaseStore;
import com.gamma.intelligence.investigation.Feedback;
import com.gamma.intelligence.investigation.FeedbackStore;
import com.gamma.intelligence.investigation.Incident;
import com.gamma.intelligence.investigation.TriageQueue;
import com.gamma.intelligence.policy.ActionRecord;
import com.gamma.intelligence.policy.AutonomyLog;
import com.gamma.intelligence.policy.AutonomyPolicyEngine;
import com.gamma.intelligence.policy.AutonomyPolicyStore;
import com.gamma.intelligence.policy.OpsMonitor;
import com.gamma.intelligence.action.ControlPlaneClient;
import com.gamma.intelligence.pack.InspectoPack;
import com.gamma.intelligence.pack.Investigator;
import com.gamma.pipeline.ComponentStore;
import com.gamma.intelligence.spi.IntelligenceAgent;
import com.gamma.service.CollectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link IntelligenceAgent} provider (AGT-5, P0): assembles the {@link InspectoPack} on the
 * eoiagent platform and hosts multi-turn {@link AgentSession}s keyed by a server-issued session id
 * (the eoiagent core has no session-id concept of its own — the host owns that mapping).
 */
public final class InspectoIntelligenceAgent implements IntelligenceAgent {

    private static final Logger log = LoggerFactory.getLogger(InspectoIntelligenceAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ARTIFACT_MIME_TYPE = "application/vnd.a2ui+json";
    private static final Set<String> ARTIFACT_KINDS = Set.of("text", "kpi", "chart", "data-table");

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    // P5: durable so the investigation corpus survives a restart and backs case-similarity recall;
    // start() replaces this with a write-root-backed instance (in-memory until then / without a root).
    private CaseStore caseStore = new CaseStore();
    // P5 (Learning): durable operator feedback on Cases (the eval-growth/tuning corpus). Always present;
    // start() replaces it with a write-root-backed instance so feedback survives a restart.
    private FeedbackStore feedback = new FeedbackStore();
    // P3 (L2): the approvals inbox + the bridge that makes eoiagent's gate non-headless. Present
    // regardless of the act tier — the inbox reads degrade to empty when the tier is off (no request
    // is ever raised then). When the tier is on, start() replaces this with a previewer-backed instance
    // and wires it as the platform's ApprovalHandler; SPI reads/decisions always go through this field.
    private AgentApprovals approvals = new AgentApprovals();
    // P4 (L3): the bounded-autonomy policy engine (kill switch + per-action-class mode/budget). Always
    // present when the module is loaded so operators can configure/read policy even before an
    // autonomous driver exists; start() replaces this with a durable-store instance. Nothing acts on
    // the policy until a driver (ops_monitor) consults authorize(...) — the engine alone is inert.
    private AutonomyPolicyEngine autonomy = new AutonomyPolicyEngine(new AutonomyPolicyStore());
    // P4 slice 2 (L3): the autonomy ledger (what ops_monitor did/why/spend) + the driver itself. The
    // ledger is always present (reads degrade to empty); the loop is opt-in and only attached in start().
    private final AutonomyLog autonomyLog = new AutonomyLog();
    private OpsMonitor opsMonitor;
    private final LlmGateway gatewayOverride;
    private CollectorService service;
    private InspectoPack pack;
    private AgentPlatform platform;
    private ContextBroker contextBroker;
    private LlmGateway gateway;
    private TriageQueue triage;

    /** Discovered/registered via {@link CollectorService}; builds its gateway from {@link GatewayFactory}. */
    public InspectoIntelligenceAgent() {
        this(null);
    }

    /** Test seam: an explicit gateway (e.g. a deterministic {@code StubLlmGateway}) skips {@link GatewayFactory}. */
    InspectoIntelligenceAgent(LlmGateway gatewayOverride) {
        this.gatewayOverride = gatewayOverride;
    }

    @Override
    public String name() {
        return "inspecto-intelligence";
    }

    @Override
    public void init(CollectorService service) {
        this.service = service;
    }

    @Override
    public void start() {
        pack = new InspectoPack(service);
        contextBroker = new ContextBroker(service);
        gateway = gatewayOverride != null ? gatewayOverride : GatewayFactory.build();
        PlatformBuilder builder = new PlatformBuilder().pack(pack).llmGateway(gateway);
        // P3 (L2): supply the human-in-the-loop approval handler only when the act tier is opted in.
        // The pack config enables MUTATING_ACTIONS off the same flag, so tools + gate move in lockstep;
        // without a handler the gate is headless (every request DENIED), so this is what makes the
        // mutating belt actually usable rather than uniformly fail-closed. The handler is backed by a
        // read-only previewer that shows the operator a diff (+ safety findings) before they approve.
        if (AgentApprovals.enabled()) {
            ComponentStore previewStore = componentStore();
            approvals = new AgentApprovals(approvalStore(),
                    call -> previewAction(previewStore, service, call));
            // P3 resume: the same instance is the platform's DecisionStore, so a re-issued run consults
            // an operator decision persisted across a restart before re-prompting. The gate's approval
            // window is set in InspectoPackConfig; a lapse still fails closed, and the persisted
            // decision, if any, resumes the next re-issue.
            builder.approvalHandler(approvals).approvalDecisionStore(approvals);
        }
        platform = builder.start();
        log.info("Intelligence platform assembled: {} v{}{}", platform.pack().name(), platform.pack().version(),
                AgentApprovals.enabled() ? " (act tier ENABLED — mutating tools gated via approvals inbox)" : "");

        // P4 (L3): the durable autonomy policy (kill switch + per-class mode/budget). Configurable via
        // GET/PUT /agent/policy regardless of any driver; a driver added later gates on autonomy.authorize.
        autonomy = new AutonomyPolicyEngine(autonomyPolicyStore());

        // P5 (Learning): durable Case corpus (backs similarity recall) + durable Case feedback. Both are
        // write-root-backed so they accrue across restarts; in-memory (as before) without a write root.
        caseStore = durableCaseStore();
        feedback = feedbackStore();

        // P4 slice 2 (L3): the ops_monitor loop is opt-in. When enabled, it watches for a
        // pipeline.batch.failed Signal and — only if the operator set batch_rerun to AUTO and there is
        // budget — replays the batch through the same audited pipeline_rerun path (actor=agent:ops-monitor).
        // With the class at its OFF default (or the kill switch engaged) it records a skip and does nothing.
        if (OpsMonitor.enabled()) {
            opsMonitor = new OpsMonitor(autonomy, autonomyLog, this::remediate);
            opsMonitor.attach(service.eventLog());
            log.info("ops_monitor enabled ({}=true): batch_rerun remediation gated by /agent/policy", OpsMonitor.ENABLED_FLAG);
        }

        // Slice E: autonomous triage is opt-in. When enabled, subscribe to the canonical Signal bus
        // and run an RCA investigation (L1 — Case + draft only) on each error/critical breach.
        if (TriageQueue.enabled()) {
            triage = new TriageQueue(this::investigate);
            triage.attach(service.eventLog());
            log.info("Autonomous triage enabled ({}=true): investigating error/critical signals", TriageQueue.ENABLED_FLAG);
        }
    }

    @Override
    public AgentSessionResult openSession(AgentSessionRequest request) {
        Role role = pack.policyProfile().mapRole(request.role());
        // S5: compose the situation frame (identity + focus + live Signal overlay) once per session
        // and supply it through eoiagent's session attributes seam; ContextBroker is deterministic and
        // budget-bounded (unit-tested). Surfacing attributes into the model prompt is the host layer's
        // concern — this wires the supply side; the tools (signals_query/signal_timeline) are the part
        // the model actively pulls from.
        Map<String, String> attributes = new HashMap<>();
        attributes.put("situation", contextBroker.frame(request.role(), request.page()));
        // Slice B: an optional goal kind (e.g. INVESTIGATION) rides the session attributes seam; the
        // eoiagent host reads "goalKind" and falls back to QA. Validate here (this module owns the
        // enum) so an unknown value fails fast — AgentRoutes maps the IllegalArgumentException to 400.
        String goalKind = normalizeGoalKind(request.goalKind());
        if (goalKind != null) attributes.put("goalKind", goalKind);
        SessionRequest sessionRequest = new SessionRequest(new UserId(UUID.randomUUID().toString()),
                role, DeploymentProfile.OFFLINE, toPageContext(request.page()), Map.copyOf(attributes));
        AgentSession session = platform.agentService().open(sessionRequest);
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, session);
        return new AgentSessionResult(sessionId, Instant.now().toString());
    }

    /** Validate an optional goal kind against the eoiagent enum → canonical name, or {@code null} when
     *  absent. Throws {@link IllegalArgumentException} for an unknown value (the route maps it to 400). */
    private static String normalizeGoalKind(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return GoalKind.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown goalKind: '" + raw + "'");
        }
    }

    @Override
    public AgentAskResult ask(String sessionId, AgentAskRequest request) {
        AgentSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("unknown intelligence session: '" + sessionId + "'");
        }
        AgentAnswer answer = session.ask(new UserMessage(request.question(), toPageContext(request.page()), Instant.now()));
        return toResult(answer);
    }

    @Override
    public void askStream(String sessionId, AgentAskRequest request, AgentAnswerSink sink) {
        AgentSession session = sessions.get(sessionId);
        if (session == null) {
            sink.onError("unknown intelligence session: '" + sessionId + "'");
            return;
        }
        UserMessage msg = new UserMessage(request.question(), toPageContext(request.page()), Instant.now());
        session.askStream(msg, new AnswerSink() {
            @Override public void onToken(String token) { sink.onToken(token); }
            @Override public void onArtifact(InlineArtifact artifact) {
                Map<String, Object> parsed = parseArtifact(artifact);
                if (parsed != null) sink.onArtifact(parsed);
            }
            @Override public void onComplete(AgentAnswer finalAnswer) { sink.onComplete(toResult(finalAnswer)); }
            @Override public void onError(EoiAgentException error) { sink.onError(error.getMessage()); }
        });
    }

    @Override
    public List<Map<String, Object>> recentCases(int limit) {
        return caseStore.recent(limit).stream().map(Case::toView).toList();
    }

    @Override
    public Optional<Map<String, Object>> caseById(String id) {
        return caseStore.byId(id).map(c -> {
            Map<String, Object> view = c.toView();
            // P5: fold the operator feedback for this Case into its detail view.
            view.put("feedback", feedback.byCaseId(id).stream().map(Feedback::toView).toList());
            return view;
        });
    }

    @Override
    public List<Map<String, Object>> similarCases(String id, int k) {
        return caseStore.byId(id)
                .map(c -> caseStore.similar(c.symptomText(), k <= 0 ? 5 : k, id))
                .orElseGet(List::of);
    }

    @Override
    public Optional<Map<String, Object>> recordCaseFeedback(String caseId, Map<String, Object> body, String submittedBy) {
        if (caseStore.byId(caseId).isEmpty()) return Optional.empty();     // unknown case → route 404
        Feedback.Rating rating = Feedback.parseRating(body == null ? null : String.valueOf(body.get("rating")));
        if (rating == null) throw new IllegalArgumentException("rating must be 'helpful' or 'not_helpful'");
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        Feedback f = new Feedback(UUID.randomUUID().toString(), caseId, rating, note,
                submittedBy == null || submittedBy.isBlank() ? "operator" : submittedBy, Instant.now());
        feedback.add(f);
        return Optional.of(f.toView());
    }

    @Override
    public List<Map<String, Object>> recentCaseFeedback(int limit) {
        return feedback.recent(limit).stream().map(Feedback::toView).toList();
    }

    @Override
    public List<Map<String, Object>> recentApprovals(int limit) {
        return approvals.recent(limit);
    }

    @Override
    public Optional<Map<String, Object>> approvalById(String id) {
        return approvals.byId(id);
    }

    @Override
    public Optional<Map<String, Object>> decideApproval(String id, boolean approve, String decidedBy) {
        return approvals.resolve(id, approve, decidedBy);
    }

    @Override
    public Optional<Map<String, Object>> autonomyPolicy() {
        return Optional.of(autonomy.current().toView());
    }

    @Override
    public Optional<Map<String, Object>> updateAutonomyPolicy(Map<String, Object> body, String updatedBy) {
        return Optional.of(autonomy.update(body, updatedBy).toView());
    }

    @Override
    public Optional<Map<String, Object>> setAutonomyKillSwitch(boolean engaged, String updatedBy) {
        return Optional.of(autonomy.setKillSwitch(engaged, updatedBy).toView());
    }

    @Override
    public List<Map<String, Object>> recentAutonomousActions(int limit) {
        return autonomyLog.recent(limit).stream().map(ActionRecord::toView).toList();
    }

    @Override
    public Optional<Map<String, Object>> autonomousActionById(String id) {
        return autonomyLog.byId(id).map(ActionRecord::toView);
    }

    /** Test seam: the policy engine, for asserting authorize verdicts + budget behaviour directly. */
    AutonomyPolicyEngine autonomy() {
        return autonomy;
    }

    /**
     * The {@code ops_monitor} remediator for the {@code batch_rerun} class (P4 slice 2): replay the
     * failed batch through the <em>same</em> audited {@code pipeline_rerun} control-plane path an
     * operator's approval would drive (never an in-process shortcut), attributed to
     * {@code agent:ops-monitor}. Returns a human detail on success; throws on a non-2xx / unreachable
     * control plane so {@link OpsMonitor} records the action as FAILED.
     */
    private String remediate(String actionClass, Map<String, Object> subject) throws Exception {
        if (!OpsMonitor.ACTION_BATCH_RERUN.equals(actionClass)) {
            throw new IllegalArgumentException("no remediator for action class '" + actionClass + "'");
        }
        String pipeline = String.valueOf(subject.get("pipeline"));
        String batchId = String.valueOf(subject.get("batchId"));
        ToolCall call = new ToolCall(OperationalActions.TOOL_PIPELINE_RERUN,
                Map.of("pipeline", pipeline, "batchId", batchId), new RunId(OpsMonitor.ACTOR_SESSION));
        ToolResult result = OperationalActions.pipelineRerun(new ControlPlaneClient(), call, OpsMonitor.ACTOR_SESSION);
        if (!result.ok()) {
            throw new IllegalStateException(result.error() == null ? "pipeline_rerun failed" : result.error());
        }
        return "reprocessed batch " + batchId + " of pipeline " + pipeline;
    }

    /**
     * Run the root-cause playbook (slice C) for an incident and file the resulting {@link Case} in the
     * store (surfaced via {@link #recentCases}/{@link #caseById}). Slice E's triage layer calls this on
     * a triggering Signal; tests call it directly. Requires {@link #start()} to have wired the gateway.
     */
    public Case investigate(Incident incident) {
        Case c = investigator().investigate(incident);
        caseStore.add(c);
        return c;
    }

    private Investigator investigator() {
        return new Investigator(service, componentStore(), service::browsableStores, gateway);
    }

    /** The registry the control routes read/write ({@code -Dassist.write.root/registry}), or {@code null}
     *  when no write root is configured. Used read-only by the P3 approval previewer and by the RCA
     *  fix-draft step; the act tools' actual writes go through the audited control plane, not this handle. */
    private static ComponentStore componentStore() {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank()
                ? null : new ComponentStore(java.nio.file.Path.of(wr).resolve("registry"));
    }

    /**
     * The approvals store. When a write root is configured ({@code -Dassist.write.root}) it is durable
     * ({@code <root>/agent/approvals.jsonl}) so pending approvals and undelivered operator decisions
     * survive a restart — the substrate for P3 resume-after-restart. Without a write root it is
     * in-memory only (dev/tests), which behaves exactly as before this slice.
     */
    private static ApprovalStore approvalStore() {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank()
                ? new ApprovalStore()
                : new ApprovalStore(java.nio.file.Path.of(wr).resolve("agent").resolve("approvals.jsonl"));
    }

    /**
     * The autonomy policy store (AGT-5 P4). Durable at {@code <assist.write.root>/agent/policy.json}
     * when a write root is set — so kill-switch state and per-class budgets survive a restart — else
     * in-memory (dev/tests).
     */
    private static AutonomyPolicyStore autonomyPolicyStore() {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank()
                ? new AutonomyPolicyStore()
                : new AutonomyPolicyStore(java.nio.file.Path.of(wr).resolve("agent").resolve("policy.json"));
    }

    /**
     * The Case-feedback store (AGT-5 P5). Durable at {@code <assist.write.root>/agent/feedback.jsonl}
     * when a write root is set — so operator ratings accrue across restarts as the learning corpus —
     * else in-memory (dev/tests).
     */
    private static FeedbackStore feedbackStore() {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank()
                ? new FeedbackStore()
                : new FeedbackStore(java.nio.file.Path.of(wr).resolve("agent").resolve("feedback.jsonl"));
    }

    /**
     * The Case store (AGT-5 P5). Durable at {@code <assist.write.root>/agent/cases.jsonl} when a write
     * root is set — so the investigation corpus survives a restart and backs similarity recall — else
     * in-memory (dev/tests), exactly as it was through P1–P4.
     */
    private static CaseStore durableCaseStore() {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank()
                ? new CaseStore()
                : new CaseStore(java.nio.file.Path.of(wr).resolve("agent").resolve("cases.jsonl"));
    }

    /**
     * The read-only dry-run preview the P3 approval gate shows the operator, dispatched by tool family:
     * operational act tools ({@code job_run}/{@code pipeline_rerun}/{@code alert_ack}/
     * {@code schedule_apply}) read live {@link CollectorService} state; {@code runbook_operator} shows
     * the full resolved plan; the component act tools diff the {@link ComponentStore}. Any other
     * (non-mutating) tool never reaches the gate, so it returns empty.
     */
    private static Map<String, Object> previewAction(ComponentStore components, CollectorService service,
                                                     ToolCall call) {
        return switch (call.toolName()) {
            case OperationalActions.TOOL_JOB_RUN, OperationalActions.TOOL_PIPELINE_RERUN,
                 OperationalActions.TOOL_ALERT_ACK, OperationalActions.TOOL_SCHEDULE_APPLY ->
                    OperationalActions.preview(service, call);
            case RunbookActions.TOOL_RUNBOOK_OPERATOR -> RunbookActions.preview(call);
            default -> ComponentActions.preview(components, call);
        };
    }

    /** Test seam: seed/inspect the store directly (e.g. before slice E's triage trigger is wired). */
    CaseStore caseStore() {
        return caseStore;
    }

    @Override
    public void close() {
        if (opsMonitor != null) {
            try { opsMonitor.close(); } catch (RuntimeException e) { log.warn("Error closing ops_monitor: {}", e.getMessage()); }
            opsMonitor = null;
        }
        if (triage != null) {
            try { triage.close(); } catch (RuntimeException e) { log.warn("Error closing triage: {}", e.getMessage()); }
            triage = null;
        }
        for (AgentSession session : sessions.values()) {
            try { session.close(); } catch (RuntimeException e) { log.warn("Error closing session: {}", e.getMessage()); }
        }
        sessions.clear();
        if (platform != null) platform.close();
    }

    // Test seam: package-private (not private) so a unit test can call this directly with a
    // hand-built AgentAnswer/InlineArtifact, bypassing the live AgentSession path (no eoiagent
    // tool/session can produce an INLINE_ARTIFACT answer today).
    static AgentAskResult toResult(AgentAnswer answer) {
        List<AgentAskResult.Citation> citations = answer.citations() == null ? List.of()
                : answer.citations().stream().map(InspectoIntelligenceAgent::toCitation).toList();
        NavigationIntent nav = answer.navigation();
        return new AgentAskResult(answer.kind().name(), answer.text(), citations,
                nav == null ? null : nav.targetPageId(), parseArtifact(answer.artifact()));
    }

    private static AgentAskResult.Citation toCitation(Citation c) {
        return new AgentAskResult.Citation(c.sourceId(), c.locator());
    }

    /**
     * Parses an {@link InlineArtifact}'s payload into our A2UI JSON convention (eoiagent defines
     * neither {@code mimeType} nor the shape inside {@code data} — that's ours to define). Fails
     * closed: any missing/unrecognized mime type, malformed JSON, or an unknown/missing
     * {@code "kind"} yields {@code null} rather than breaking the answer.
     */
    private static Map<String, Object> parseArtifact(InlineArtifact artifact) {
        if (artifact == null) return null;
        if (!ARTIFACT_MIME_TYPE.equals(artifact.mimeType())) return null;
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(artifact.data(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException | RuntimeException e) {
            log.warn("Dropping malformed A2UI artifact: {}", e.getMessage());
            return null;
        }
        Object kind = parsed.get("kind");
        if (!(kind instanceof String s) || !ARTIFACT_KINDS.contains(s)) {
            log.warn("Dropping A2UI artifact with missing/unknown kind: {}", kind);
            return null;
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private static PageContext toPageContext(Map<String, Object> page) {
        if (page == null || page.isEmpty()) return null;
        String pageId = page.get("pageId") == null ? null : String.valueOf(page.get("pageId"));
        return new PageContext(pageId, stringMap(page.get("entityIds")), stringMap(page.get("filters")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return out;
    }
}
