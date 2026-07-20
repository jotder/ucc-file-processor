package com.gamma.intelligence.action;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One request for human approval of a mutating agent tool call — AGT-5 P3 (autonomy ladder L2,
 * {@code docs/superpower/embedded-intelligence-plan.md} §6). Built by {@link AgentApprovals} when the
 * eoiagent gate routes a mutating call through its {@code ApprovalHandler}; surfaced to the operator
 * via {@code GET /agent/approvals*} and resolved by {@code POST /agent/approvals/{id}/decision}.
 *
 * <p>Request facts (tool, arguments, dry-run {@code preview}, {@code agentActor}) are immutable; only
 * the decision fields transition, exactly once, {@code PENDING → APPROVED/DENIED/TIMED_OUT} — that
 * transition is guarded by {@link ApprovalStore} so a double-decision can never re-open a lapsed one.
 */
public final class Approval {

    /** Terminal outcomes mirror eoiagent's {@code ApprovalDecision} plus the initial {@code PENDING}. */
    public enum Status { PENDING, APPROVED, DENIED, TIMED_OUT }

    private final String id;
    private final String toolName;
    /** How the ensuing control-plane mutation is audited — {@code "agent:<runId>"} (see {@code ApiContext.actor}). */
    private final String agentActor;
    private final String summary;
    private final Map<String, Object> arguments;
    private final Map<String, Object> preview;
    private final Instant requestedAt;

    private volatile Status status = Status.PENDING;
    private volatile Instant decidedAt;
    private volatile String decidedBy;

    public Approval(String id, String toolName, String agentActor, String summary,
                    Map<String, Object> arguments, Map<String, Object> preview, Instant requestedAt) {
        this.id = id;
        this.toolName = toolName;
        this.agentActor = agentActor;
        this.summary = summary;
        this.arguments = copy(arguments);
        this.preview = copy(preview);
        this.requestedAt = requestedAt;
    }

    /** Defensive, null-value-tolerant copy (model-supplied arguments may carry JSON nulls). */
    private static Map<String, Object> copy(Map<String, Object> m) {
        return m == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    public String id() { return id; }
    public String toolName() { return toolName; }
    public String agentActor() { return agentActor; }
    public Status status() { return status; }

    /** Record the terminal decision. Package-private: only {@link ApprovalStore} transitions state. */
    void decide(Status terminal, String by, Instant at) {
        this.status = terminal;
        this.decidedBy = by;
        this.decidedAt = at;
    }

    /** The {@code GET /agent/approvals*} view — a plain, JSON-friendly map (the core stays free of this type). */
    public Map<String, Object> toView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("tool", toolName);
        m.put("agentActor", agentActor);
        m.put("summary", summary);
        m.put("arguments", arguments);
        m.put("preview", preview);
        m.put("status", status.name());
        m.put("requestedAt", requestedAt.toString());
        m.put("decidedAt", decidedAt == null ? null : decidedAt.toString());
        m.put("decidedBy", decidedBy);
        return m;
    }
}
