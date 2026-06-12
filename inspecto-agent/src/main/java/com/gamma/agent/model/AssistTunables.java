package com.gamma.agent.model;

/**
 * Deployment tunables for the assist layer (v4.1, B1 hardening) — the previously hardcoded magic
 * numbers, now resolvable per deployment without a rebuild. Each knob resolves:
 * <ol>
 *   <li>system property (e.g. {@code -Dassist.repair.rounds=5}),</li>
 *   <li>env var (upper-snake: {@code ASSIST_REPAIR_ROUNDS}),</li>
 *   <li>extra key in the assist settings file ({@link AssistModelSettings#path()}),</li>
 *   <li>the caller-supplied default (the historical constant).</li>
 * </ol>
 * Values are read once per JVM at skill class-init (these are risk-posture knobs, not live state);
 * a misparse falls back to the default rather than failing startup.
 *
 * <h3>Knobs</h3>
 * <pre>
 *   assist.repair.rounds          generate→validate→repair attempts   (skills default 2–3)
 *   assist.confidence.threshold   escalation abstain floor             (default 0.5)
 *   assist.explain.neighbours     explain-entity graph fan-out         (default 8)
 *   assist.explain.doc.snippets   explain-entity doc-RAG snippets      (default 3)
 *   assist.reactor.queue          failure-reactor queue capacity       (default 1024)
 * </pre>
 */
public final class AssistTunables {

    private AssistTunables() {}

    public static int repairRounds(int skillDefault) {
        return intOf("assist.repair.rounds", skillDefault);
    }

    public static double confidenceThreshold(double skillDefault) {
        String v = lookup("assist.confidence.threshold");
        if (v != null) {
            try {
                double d = Double.parseDouble(v);
                if (d >= 0.0 && d <= 1.0) return d;
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return skillDefault;
    }

    public static int explainNeighbours(int skillDefault) {
        return intOf("assist.explain.neighbours", skillDefault);
    }

    public static int explainDocSnippets(int skillDefault) {
        return intOf("assist.explain.doc.snippets", skillDefault);
    }

    public static int reactorQueueCapacity(int defaultCapacity) {
        return intOf("assist.reactor.queue", defaultCapacity);
    }

    private static int intOf(String key, int def) {
        String v = lookup(key);
        if (v != null) {
            try {
                int i = Integer.parseInt(v.trim());
                if (i > 0) return i;
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return def;
    }

    private static String lookup(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key.toUpperCase().replace('.', '_'));
        if (v == null || v.isBlank()) v = AssistModelSettings.extraProperty(key);
        return (v == null || v.isBlank()) ? null : v;
    }
}
