package com.gamma.intelligence.policy;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in the autonomy ledger (AGT-5 P4): a single decision an autonomous driver (the
 * {@code ops_monitor} loop) reached for one triggering subject — <em>what</em> it considered, the
 * policy <em>verdict</em> and its reason, and the <em>outcome</em> of acting (or not). This is the
 * "what the agent did, why, and spend" record the autonomy dashboard reads; it is written for every
 * decision, including denials and shadow-mode no-ops, so the trail is complete.
 */
public final class ActionRecord {

    /** What became of the action after the policy verdict. */
    public enum Status {
        /** Policy denied (kill switch / OFF / budget exhausted) — nothing ran. */
        SKIPPED,
        /** Shadow mode — evaluated and logged what it would do, but did not execute. */
        SHADOWED,
        /** Executed autonomously and the remediation succeeded. */
        SUCCEEDED,
        /** Executed autonomously but the remediation failed. */
        FAILED
    }

    private final String id;
    private final String actionClass;
    private final Map<String, Object> subject;
    private final String decision;   // the AutonomyPolicyEngine.Outcome name (ALLOW/SHADOW/DENY)
    private final String reason;      // the verdict reason
    private final Status status;
    private final String detail;      // human summary of what happened (e.g. "reprocessed batch …" / error)
    private final Instant at;

    public ActionRecord(String id, String actionClass, Map<String, Object> subject, String decision,
                        String reason, Status status, String detail, Instant at) {
        this.id = id;
        this.actionClass = actionClass;
        this.subject = subject == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(subject));
        this.decision = decision;
        this.reason = reason;
        this.status = status;
        this.detail = detail;
        this.at = at;
    }

    public String id() { return id; }
    public Status status() { return status; }

    /** The {@code GET /agent/actions} view — a plain, JSON-friendly map (the core stays free of this type). */
    public Map<String, Object> toView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("actionClass", actionClass);
        m.put("subject", subject);
        m.put("decision", decision);
        m.put("reason", reason);
        m.put("status", status.name());
        m.put("detail", detail);
        m.put("at", at.toString());
        return m;
    }
}
