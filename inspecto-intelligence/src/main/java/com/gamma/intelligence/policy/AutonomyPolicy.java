package com.gamma.intelligence.policy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The bounded-autonomy policy (AGT-5 P4, ladder L3, {@code docs/superpower/embedded-intelligence-plan.md}
 * §6). One document per process, owned by {@link AutonomyPolicyStore} and evaluated by
 * {@link AutonomyPolicyEngine} before any <em>autonomous</em> (un-prompted) action runs.
 *
 * <p>Two independent controls:
 * <ul>
 *   <li><b>Kill switch</b> — a single global hard-off. When engaged, {@link AutonomyPolicyEngine}
 *       denies every action class regardless of its mode; the plan's "hard-off switch proven live".
 *       Disengaged by default (so configuring a class is what turns autonomy on, not this).</li>
 *   <li><b>Per-action-class policy</b> — a {@link ClassPolicy} keyed by a stable action-class id
 *       (e.g. {@code "batch_rerun"}). An <em>unconfigured</em> class is treated as {@link Mode#OFF},
 *       so the safe default is "no class acts autonomously until an operator opts it in".</li>
 * </ul>
 *
 * <p>Immutable; edits produce a new instance via {@link #withClass}/{@link #withKillSwitch}.
 */
public record AutonomyPolicy(boolean killSwitch, Map<String, ClassPolicy> classes,
                             Instant updatedAt, String updatedBy) {

    /** How an action class may run autonomously. */
    public enum Mode {
        /** Never autonomous — falls back to the L2 approval gate / L1 draft. The default for any class. */
        OFF,
        /** Evaluate and record what it <em>would</em> do, but never execute — a dry-run pilot. */
        SHADOW,
        /** Execute autonomously, within the class budget. */
        AUTO
    }

    /**
     * Per-class settings. {@code maxPerHour}/{@code maxPerDay} are the L3 budget: the count of
     * autonomous executions allowed in a rolling window ({@code <= 0} means "no limit on that window").
     */
    public record ClassPolicy(Mode mode, int maxPerHour, int maxPerDay) {
        public ClassPolicy {
            mode = mode == null ? Mode.OFF : mode;
        }

        public Map<String, Object> toView() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", mode.name());
            m.put("maxPerHour", maxPerHour);
            m.put("maxPerDay", maxPerDay);
            return m;
        }

        static ClassPolicy fromView(Map<String, Object> m) {
            return new ClassPolicy(parseMode(m.get("mode")), intOr(m.get("maxPerHour"), 0), intOr(m.get("maxPerDay"), 0));
        }
    }

    public AutonomyPolicy {
        classes = classes == null ? Map.of() : Map.copyOf(classes);
    }

    /** The initial policy: nothing killed, no class configured (every class defaults to {@link Mode#OFF}). */
    public static AutonomyPolicy defaults() {
        return new AutonomyPolicy(false, Map.of(), Instant.now(), "system");
    }

    /** The effective policy for a class — the configured one, or an {@code OFF} default. */
    public ClassPolicy classPolicy(String actionClass) {
        ClassPolicy c = classes.get(actionClass);
        return c != null ? c : new ClassPolicy(Mode.OFF, 0, 0);
    }

    public AutonomyPolicy withClass(String actionClass, ClassPolicy policy, String by) {
        Map<String, ClassPolicy> next = new LinkedHashMap<>(classes);
        next.put(Objects.requireNonNull(actionClass, "actionClass"), Objects.requireNonNull(policy, "policy"));
        return new AutonomyPolicy(killSwitch, next, Instant.now(), by);
    }

    public AutonomyPolicy withKillSwitch(boolean engaged, String by) {
        return new AutonomyPolicy(engaged, classes, Instant.now(), by);
    }

    /** The {@code GET /agent/policy} view — a plain, JSON-friendly map (the core stays free of this type). */
    public Map<String, Object> toView() {
        Map<String, Object> cls = new LinkedHashMap<>();
        new TreeMap<>(classes).forEach((k, v) -> cls.put(k, v.toView())); // stable key order
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("killSwitch", killSwitch);
        m.put("classes", cls);
        m.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        m.put("updatedBy", updatedBy);
        return m;
    }

    Map<String, Object> toRecord() { return toView(); }

    /** Rehydrate from a persisted {@link #toRecord()} / an operator PUT body. Tolerant of partial maps. */
    @SuppressWarnings("unchecked")
    static AutonomyPolicy fromRecord(Map<String, Object> m) {
        boolean kill = Boolean.TRUE.equals(m.get("killSwitch"));
        Map<String, ClassPolicy> classes = new LinkedHashMap<>();
        Object raw = m.get("classes");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> cp) {
                    classes.put(String.valueOf(e.getKey()), ClassPolicy.fromView((Map<String, Object>) cp));
                }
            }
        }
        Object at = m.get("updatedAt");
        String by = m.get("updatedBy") == null ? "system" : String.valueOf(m.get("updatedBy"));
        return new AutonomyPolicy(kill, classes,
                at == null ? Instant.now() : Instant.parse(String.valueOf(at)), by);
    }

    private static Mode parseMode(Object v) {
        if (v == null) return Mode.OFF;
        try {
            return Mode.valueOf(String.valueOf(v).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.OFF;
        }
    }

    private static int intOr(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return dflt;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
