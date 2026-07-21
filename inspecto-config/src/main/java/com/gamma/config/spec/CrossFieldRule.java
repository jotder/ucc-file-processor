package com.gamma.config.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A validation rule that spans more than one field — the declarative form of the implicit invariants
 * scattered through {@code PipelineConfig.load} (plugin-ingester-requires-segments) and
 * {@code ConfigValidator} (threads×duckdb oversubscription, engine×skip-tail, threads-vs-batch,
 * dup-check retention).
 *
 * <p>The {@link #rule} predicate is evaluated against the decoded raw config map and returns
 * <b>{@code true} when the configuration is acceptable</b>. A {@code false} result produces a
 * {@link Finding} carrying this rule's {@link #severity} and {@link #description}. (Phrasing the
 * predicate as "is satisfied" rather than "is violated" keeps the rule list readable as a set of
 * invariants that must hold.)
 *
 * <p>The predicate is {@link JsonIgnore}d so the rule still serialises cleanly: a UI or LLM reads
 * {@code id}/{@code description}/{@code severity}/{@code affectedPaths} to explain the constraint
 * without needing the executable logic.
 *
 * @param id            stable rule identifier (e.g. {@code "plugin-ingester-requires-segments"})
 * @param description   human-readable statement of the invariant + how to fix a violation
 * @param severity      ERROR (cannot run) or WARNING (suspicious but legal)
 * @param affectedPaths the field paths this rule reasons about (for UI anchoring; never {@code null})
 * @param rule          returns {@code true} when the config satisfies the invariant
 */
public record CrossFieldRule(String id, String description, Severity severity,
                             List<String> affectedPaths,
                             @JsonIgnore Predicate<Map<String, Object>> rule) {

    public CrossFieldRule {
        id = id == null ? "" : id;
        description = description == null ? "" : description;
        severity = severity == null ? Severity.ERROR : severity;
        affectedPaths = affectedPaths == null ? List.of() : List.copyOf(affectedPaths);
    }

    /**
     * Evaluate the rule against a decoded config map.
     *
     * @return an empty optional when the invariant holds, otherwise a {@link Finding} anchored to
     *         the rule's first affected path (or blank when the rule names none)
     */
    public java.util.Optional<Finding> check(Map<String, Object> raw) {
        if (rule == null || rule.test(raw)) {
            return java.util.Optional.empty();
        }
        String anchor = affectedPaths.isEmpty() ? "" : affectedPaths.get(0);
        return java.util.Optional.of(new Finding(severity, anchor, description));
    }
}
