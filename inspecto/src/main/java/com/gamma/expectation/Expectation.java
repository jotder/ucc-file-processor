package com.gamma.expectation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One data-quality <b>Expectation</b> (ING-6) — the data-quality third of the Rules triad (Expectation /
 * Alert Rule / Decision Rule; see {@code docs/GLOSSARY.md}). An Expectation validates the records of a
 * target's at-rest data against a Schema-level constraint on one {@code column}; a failing evaluation
 * counts violating records, opens a correlated Incident and fans out an {@code EXPECTATION_FAILED} signal.
 *
 * <p>Authored over HTTP ({@code /expectations*}) and persisted as an {@code expectation} component (its
 * {@code lastResult}/{@code createdAt}/{@code updatedAt} bookkeeping ride the stored content alongside
 * these validated fields). This record is the validated <em>config</em> shape; {@link #fromMap} mirrors
 * the mock contract in {@code expectations.handler.ts} and {@link ExpectationEvaluator} turns it into the
 * server-built violation-count SQL.
 *
 * <pre>
 *   kind         non_null | range | regex | referential | condition
 *   targetType   pipeline | job                 # the target's at-rest data is queried
 *   target       &lt;name&gt;                        # physicalRef under the space data root
 *   column       &lt;identifier&gt;                  # the column the check applies to (not 'condition')
 *   min,max      &lt;number&gt;                       # range bounds (at least one)
 *   pattern      &lt;regex&gt;                        # regex the value must match
 *   refDataset,refColumn                         # referential lookup relation + column
 *   when         &lt;condition tree&gt;               # 'condition' kind: the violation predicate itself
 *   severity     MINOR | MAJOR | CRITICAL        # incident/signal severity on failure
 *   enabled      bool                            # evaluate-all skips disabled expectations
 * </pre>
 *
 * <p><b>{@code condition} kind (Rules triad condition-tree promotion, 2026-07-18)</b> — the author
 * writes an arbitrary {@code when} condition tree (the same {@code query-types} shape Decision Rules
 * author); {@link com.gamma.query.ConditionSql} compiles it straight to the violation predicate, so
 * unlike the other four kinds it needs no {@code column} — the tree names its own field(s), and can
 * span several columns or use any {@code query-types} operator {@code non_null}/{@code range} can't
 * (e.g. {@code contains}, multi-column {@code AND}/{@code OR}). {@code when} is required for this
 * kind and ignored for the other four (each keeps its own hand-built predicate).
 */
public record Expectation(String name, String description, String targetType, String target, String column,
                          String kind, Double min, Double max, String pattern,
                          String refDataset, String refColumn, Object when, String severity, boolean enabled) {

    public static final Set<String> KINDS = Set.of("non_null", "range", "regex", "referential", "condition");
    public static final Set<String> TARGET_TYPES = Set.of("pipeline", "job");
    public static final Set<String> SEVERITIES = Set.of("MINOR", "MAJOR", "CRITICAL");

    public Expectation {
        require(name != null && !name.isBlank(), "expectation.name is required");
        kind = lower(kind);
        require(KINDS.contains(kind), "expectation.kind must be one of " + KINDS);
        targetType = targetType == null ? "pipeline" : targetType.trim().toLowerCase(Locale.ROOT);
        require(TARGET_TYPES.contains(targetType), "expectation.targetType must be one of " + TARGET_TYPES);
        require(target != null && !target.isBlank(), "expectation.target is required");
        boolean isCondition = "condition".equals(kind);
        require(isCondition || (column != null && !column.isBlank()),
                "expectation.column is required (except for kind: condition)");
        target = target.trim();
        column = column == null ? null : column.trim();
        severity = severity == null ? "MAJOR" : severity.trim().toUpperCase(Locale.ROOT);
        require(SEVERITIES.contains(severity), "expectation.severity must be one of " + SEVERITIES);
        pattern = blankToNull(pattern);
        refDataset = blankToNull(refDataset);
        refColumn = blankToNull(refColumn);
        when = (when instanceof Map<?, ?> m && !m.isEmpty()) ? when : null;

        switch (kind) {
            case "range" -> require(min != null || max != null,
                    "expectation.range needs at least one of min/max");
            case "regex" -> require(pattern != null, "expectation.regex needs a pattern");
            case "referential" -> require(refDataset != null && refColumn != null,
                    "expectation.referential needs refDataset and refColumn");
            case "condition" -> require(when != null, "expectation.condition needs a 'when' condition tree");
            default -> { /* non_null carries no extra params */ }
        }
    }

    /** Parse + validate from the decoded expectation map (the upsert body or a stored component's content). */
    public static Expectation fromMap(Map<String, Object> m) {
        require(m != null, "missing expectation body");
        return new Expectation(
                str(m.get("name")),
                str(m.get("description")),
                str(m.get("targetType")),
                str(m.get("target")),
                str(m.get("column")),
                str(m.get("kind")),
                number(m.get("min")),
                number(m.get("max")),
                str(m.get("pattern")),
                str(m.get("refDataset")),
                str(m.get("refColumn")),
                m.get("when"),
                str(m.get("severity")),
                m.get("enabled") == null || !"false".equalsIgnoreCase(String.valueOf(m.get("enabled"))));
    }

    /** JSON/TOON-ready view of the validated config fields (bookkeeping keys are added by the route). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description == null ? "" : description);
        m.put("targetType", targetType);
        m.put("target", target);
        m.put("column", column);
        m.put("kind", kind);
        m.put("min", min);
        m.put("max", max);
        m.put("pattern", pattern);
        m.put("refDataset", refDataset);
        m.put("refColumn", refColumn);
        if (when != null) m.put("when", when);
        m.put("severity", severity);
        m.put("enabled", enabled);
        return m;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String lower(String v) {
        return v == null ? null : v.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Double number(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expectation numeric bound must be a number, got '" + s + "'");
        }
    }
}
