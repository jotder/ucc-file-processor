package com.gamma.intelligence.policy;

import com.gamma.intelligence.policy.AutonomyPolicy.ClassPolicy;
import com.gamma.intelligence.policy.AutonomyPolicy.Mode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The decision point for bounded autonomy (AGT-5 P4, L3). Any autonomous driver (e.g. the
 * {@code ops_monitor} loop) calls {@link #authorize(String)} <em>before</em> acting; the verdict folds
 * three controls in priority order: the global kill switch, the action class's {@link Mode}, and its
 * rolling-window {@link ActionBudget}. An {@link Outcome#ALLOW} verdict has already consumed one unit
 * of budget, so a caller must either execute or explicitly treat a non-execution as a refund concern
 * (there is no refund — an ALLOW that the caller drops simply spent budget, which fails safe).
 *
 * <p>Reads/writes of the policy funnel through here so the store and the in-memory budget stay a unit;
 * the store owns durability, this owns evaluation. Thread-safe: the store and budget are each
 * internally synchronized.
 */
public final class AutonomyPolicyEngine {

    /** What the caller may do with an action right now. */
    public enum Outcome {
        /** Execute now — one unit of budget has been consumed. */
        ALLOW,
        /** Evaluate + log what it would do, but do not execute (shadow pilot). No budget consumed. */
        SHADOW,
        /** Do not act — kill switch, class OFF, or budget exhausted. */
        DENY
    }

    /** The authorize result: an {@link Outcome} plus a human reason for the audit/dashboard. */
    public record Verdict(Outcome outcome, String reason) {
        public boolean allowed() { return outcome == Outcome.ALLOW; }
        public boolean shadow() { return outcome == Outcome.SHADOW; }

        public Map<String, Object> toView() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", outcome.name());
            m.put("reason", reason);
            return m;
        }
    }

    private final AutonomyPolicyStore store;
    private final ActionBudget budget;

    public AutonomyPolicyEngine(AutonomyPolicyStore store) {
        this(store, new ActionBudget());
    }

    public AutonomyPolicyEngine(AutonomyPolicyStore store, ActionBudget budget) {
        this.store = store;
        this.budget = budget;
    }

    /**
     * Decide whether {@code actionClass} may act autonomously right now. On {@link Outcome#ALLOW} a
     * budget unit is consumed atomically, so concurrent callers can never both exceed the cap.
     */
    public Verdict authorize(String actionClass) {
        AutonomyPolicy policy = store.current();
        if (policy.killSwitch()) {
            return new Verdict(Outcome.DENY, "kill switch engaged");
        }
        ClassPolicy cp = policy.classPolicy(actionClass);
        switch (cp.mode()) {
            case OFF:
                return new Verdict(Outcome.DENY, "action class '" + actionClass + "' is off");
            case SHADOW:
                return new Verdict(Outcome.SHADOW, "shadow mode: would act, execution suppressed");
            case AUTO:
                if (budget.tryConsume(actionClass, cp.maxPerHour(), cp.maxPerDay())) {
                    return new Verdict(Outcome.ALLOW, "within budget");
                }
                return new Verdict(Outcome.DENY, budgetReason(cp));
            default:
                return new Verdict(Outcome.DENY, "unknown mode");
        }
    }

    private static String budgetReason(ClassPolicy cp) {
        StringBuilder sb = new StringBuilder("budget exhausted (");
        if (cp.maxPerHour() > 0) sb.append("maxPerHour=").append(cp.maxPerHour());
        if (cp.maxPerDay() > 0) sb.append(cp.maxPerHour() > 0 ? ", " : "").append("maxPerDay=").append(cp.maxPerDay());
        return sb.append(')').toString();
    }

    public AutonomyPolicy current() {
        return store.current();
    }

    /** Replace the whole policy from an operator PUT body (tolerant of partial maps). */
    public AutonomyPolicy update(Map<String, Object> body, String by) {
        AutonomyPolicy parsed = AutonomyPolicy.fromRecord(body == null ? Map.of() : body);
        return store.update(new AutonomyPolicy(parsed.killSwitch(), parsed.classes(), java.time.Instant.now(),
                by == null || by.isBlank() ? "operator" : by.trim()));
    }

    /** The one-call emergency stop / resume (plan: "hard-off switch proven live"). */
    public AutonomyPolicy setKillSwitch(boolean engaged, String by) {
        return store.update(store.current().withKillSwitch(engaged, safe(by)));
    }

    /** Configure a single action class (mode + budget) without replacing the rest of the policy. */
    public AutonomyPolicy setClass(String actionClass, ClassPolicy classPolicy, String by) {
        return store.update(store.current().withClass(actionClass, classPolicy, safe(by)));
    }

    /** Budget headroom for the dashboard: remaining executions today per configured class. */
    public Map<String, Integer> remainingToday() {
        Map<String, Integer> out = new LinkedHashMap<>();
        AutonomyPolicy policy = store.current();
        policy.classes().forEach((cls, cp) ->
                out.put(cls, budget.remainingToday(cls, cp.maxPerDay())));
        return out;
    }

    private static String safe(String by) {
        return by == null || by.isBlank() ? "operator" : by.trim();
    }
}
