package com.gamma.agent.skill;

import com.gamma.agent.kernel.reason.RepairLoop;
import com.gamma.config.spec.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic oracle for {@code diagnose-and-alert}'s NL→alert-rule mode (v3.7.0, M7). It
 * validates the <em>shape</em> of a drafted alert rule — required fields, enum membership, numeric
 * bounds, and (when present) that {@code onPipeline} names a real catalog SOURCE — returning
 * ERROR-severity {@link Finding}s in the same idiom as {@code ConfigSafetyValidator} /
 * {@code ConfigSpecs}. It never throws; the caller turns any finding into a {@link RepairLoop} retry.
 *
 * <p>This is an <b>agent-side</b> validator rather than a core {@code ConfigSpec}: nothing in the
 * core engine executes alert rules yet (the milestone is draft-only, V-9), so the rule shape lives
 * with the skill that drafts it. Like every oracle in the assist layer it guards structure, not
 * intent — a well-formed rule can still be the wrong rule, which is why the skill surfaces a
 * {@code humanReadable} description for the operator to confirm.
 */
public final class AlertRuleValidator {

    private AlertRuleValidator() {}

    /** The metrics an alert rule may watch (over the audit/status stores). */
    public static final Set<String> METRICS =
            Set.of("error_rate", "failed_batches", "rejected_files", "duration_ms");
    /** The comparison operators a rule may use. */
    public static final Set<String> COMPARATORS = Set.of("gt", "gte", "lt", "lte");
    /** The severities a rule may raise. */
    public static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");

    /**
     * Validate a drafted rule map (the {@code alert} section's contents) against the shape rules.
     *
     * @param rule           the rule fields ({@code name, metric, comparator, threshold, window, severity, onPipeline?})
     * @param knownPipelines the set of real pipeline names (for grounding {@code onPipeline})
     * @return ERROR findings for every violation (empty when the rule is well-formed)
     */
    public static List<Finding> check(Map<String, Object> rule, Set<String> knownPipelines) {
        List<Finding> findings = new ArrayList<>();
        if (rule == null) {
            findings.add(Finding.error("alert", "alert rule is missing"));
            return findings;
        }

        requireNonBlank(findings, rule, "name");

        String metric = str(rule.get("metric"));
        if (metric == null) findings.add(Finding.error("alert.metric", "missing required field 'metric'"));
        else if (!METRICS.contains(metric))
            findings.add(Finding.error("alert.metric",
                    "metric '" + metric + "' is not one of " + sorted(METRICS)));

        String comparator = str(rule.get("comparator"));
        if (comparator == null) findings.add(Finding.error("alert.comparator", "missing required field 'comparator'"));
        else if (!COMPARATORS.contains(comparator))
            findings.add(Finding.error("alert.comparator",
                    "comparator '" + comparator + "' is not one of " + sorted(COMPARATORS)));

        Double threshold = number(rule.get("threshold"));
        if (threshold == null) {
            findings.add(Finding.error("alert.threshold", "missing or non-numeric required field 'threshold'"));
        } else if (threshold <= 0) {
            findings.add(Finding.error("alert.threshold", "threshold must be > 0 (was " + threshold + ")"));
        } else if ("error_rate".equals(metric) && threshold > 1) {
            findings.add(Finding.error("alert.threshold",
                    "error_rate is a fraction in (0,1]; threshold " + threshold
                            + " is out of range (use 0.05 for 5%)"));
        }

        String window = str(rule.get("window"));
        if (window == null) findings.add(Finding.error("alert.window", "missing required field 'window'"));
        else if (!isWindow(window))
            findings.add(Finding.error("alert.window",
                    "window '" + window + "' is not a duration like 1h, 30m, 1d, or a batch count like 20b"));

        String severity = str(rule.get("severity"));
        if (severity == null) findings.add(Finding.error("alert.severity", "missing required field 'severity'"));
        else if (!SEVERITIES.contains(severity.toUpperCase()))
            findings.add(Finding.error("alert.severity",
                    "severity '" + severity + "' is not one of " + sorted(SEVERITIES)));

        String onPipeline = str(rule.get("onPipeline"));
        if (onPipeline != null && !knownPipelines.contains(onPipeline))
            findings.add(Finding.error("alert.onPipeline",
                    "onPipeline '" + onPipeline + "' is not a known pipeline; use one of "
                            + knownPipelines + " or omit it"));

        return findings;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void requireNonBlank(List<Finding> out, Map<String, Object> rule, String field) {
        if (str(rule.get(field)) == null)
            out.add(Finding.error("alert." + field, "missing required field '" + field + "'"));
    }

    /** A window is a duration (number + s/m/h/d) or a batch count (number + b). */
    private static boolean isWindow(String w) {
        return w.matches("(?i)\\d+\\s*[smhdb]");
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return (s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    private static Double number(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        String s = str(v);
        if (s == null) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static List<String> sorted(Set<String> s) {
        return s.stream().sorted().toList();
    }
}
