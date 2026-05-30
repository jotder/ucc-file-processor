package com.gamma.catalog.spi;

import com.gamma.catalog.Description;

import java.util.List;

/**
 * Fills <em>empty</em> column/table descriptions in the metadata graph. This is the seam through
 * which AI-authored descriptions enter: the lean core ships only a no-op provider, and the
 * {@code file-processor-agent} module contributes an AI-backed provider at M3 via
 * {@link java.util.ServiceLoader} — with no change to core.
 *
 * <p>Providers are consulted only for nodes whose description has {@link com.gamma.catalog.Provenance#NONE}.
 * Operator-authored ({@code MANUAL}) prose is never overwritten — the graph builder merges results
 * with {@link Description#mergePreferring}, so authority always wins. A provider returns
 * {@link Description#EMPTY} to abstain, and must never throw (the builder treats a thrown
 * exception as an abstention).
 */
public interface DescriptionProvider {

    /** Short identifier for logging/diagnostics (e.g. {@code "noop"}, {@code "ollama-qwen"}). */
    String name();

    /** Suggest a description for a column, or {@link Description#EMPTY} to abstain. */
    Description describeColumn(ColumnContext ctx);

    /** Suggest a description for a table, or {@link Description#EMPTY} to abstain. */
    default Description describeTable(TableContext ctx) {
        return Description.EMPTY;
    }

    /** Light context for describing a column. */
    record ColumnContext(String pipeline, String table, String columnName,
                         String type, String existingDescription) {}

    /** Light context for describing a table. */
    record TableContext(String tableId, String label, List<String> columnNames) {}
}
