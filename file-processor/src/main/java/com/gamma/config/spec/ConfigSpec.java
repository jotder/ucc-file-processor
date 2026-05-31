package com.gamma.config.spec;

import java.util.List;

/**
 * The complete declarative specification of one configuration type — the wire DTO returned by
 * {@code GET /config/spec/{type}} and the input to {@code ConfigLoader.validate}.
 *
 * <p>A {@code ConfigSpec} bundles the per-field rules ({@link #fields}) with the rules that span
 * fields ({@link #rules}). Together they are the single source of truth the design calls for: one
 * declaration that drives parsing defaults, structured validation, UI form generation, and
 * LLM-constrained authoring — so none of those consumers has to re-derive the rules from the Java
 * {@code load} code.
 *
 * @param type   the config type this describes ({@code pipeline}/{@code enrichment}/{@code job}/
 *               {@code schema}/{@code meta})
 * @param fields the per-field specs (never {@code null})
 * @param rules  the cross-field rules (never {@code null})
 */
public record ConfigSpec(String type, List<FieldSpec> fields, List<CrossFieldRule> rules) {

    public ConfigSpec {
        type = type == null ? "" : type;
        fields = fields == null ? List.of() : List.copyOf(fields);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** The {@link FieldSpec} at {@code path}, if declared. */
    public java.util.Optional<FieldSpec> field(String path) {
        return fields.stream().filter(f -> f.path().equals(path)).findFirst();
    }
}
