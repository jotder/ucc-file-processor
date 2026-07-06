package com.gamma.agent.kernel.tool;

import java.util.List;

/**
 * The output of a {@link Tool}: a computed {@code value}, the {@link Evidence} backing it, and a
 * {@code hasData} flag distinguishing "computed nothing" (abstain-worthy) from "computed a value".
 */
public record ToolResult(Object value, List<Evidence> evidence, boolean hasData) {

    public ToolResult {
        evidence = (evidence == null) ? List.of() : List.copyOf(evidence);
    }

    /** A result carrying a value and its supporting evidence. */
    public static ToolResult of(Object value, List<Evidence> evidence) {
        return new ToolResult(value, evidence, true);
    }

    /** No data available — the caller should abstain rather than invent an answer. */
    public static ToolResult noData() {
        return new ToolResult(null, List.of(), false);
    }
}
