package com.gamma.etl;

import java.io.File;
import java.util.List;

/**
 * A unit of processing: one or more member files sharing a single schema/table,
 * processed in one pass into consolidated partition output.
 *
 * @param batchId    unique id, e.g. {@code "20260527_103000_mini_0001"}
 * @param schemaName human-readable schema name (from {@code raw.name})
 * @param table      output table sub-directory under {@code dirs.database}; may be {@code null}
 * @param members    member files in deterministic order, each with its 0-based {@code srcId}
 */
public record Batch(String batchId, String schemaName, String table, List<Member> members) {

    /**
     * @param file      the member input file
     * @param srcId     0-based index within the batch (used as the {@code __src_id} tag)
     * @param bytes     file size in bytes (used during planning)
     * @param selection resolved schema + table for this file
     */
    public record Member(File file, int srcId, long bytes, SchemaSelector.Selection selection) {}
}
