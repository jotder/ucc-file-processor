package com.gamma.inspector;

import com.gamma.etl.Batch;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PartitionOutput;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The result of a {@link BatchIngestStrategy#ingest} pass — everything
 * {@link BatchProcessor} needs to commit and audit one batch, independent of which
 * ingest path produced it.
 *
 * @param batchStart     when ingest began (audit start timestamp)
 * @param status         {@code "SUCCESS"}, {@code "EMPTY"}, or {@code "FAILED"}
 * @param error          failure message ({@code ""} when not failed)
 * @param survivors      members that contributed accepted rows (drive commit)
 * @param memberAudits   per-input-file audit rows (drive the file-level audit)
 * @param outputs        partition files written
 * @param lineage        input→output row-count matrix
 * @param totalInputRows total accepted input rows across all members
 * @param schemaLabel    audit schema label — {@code batch.schemaName()} for CSV, or the
 *                       comma-joined segment keys for the plugin path
 */
record IngestOutcome(LocalDateTime batchStart,
                     String status,
                     String error,
                     List<Batch.Member> survivors,
                     List<MemberAudit> memberAudits,
                     List<PartitionOutput> outputs,
                     List<LineageRow> lineage,
                     long totalInputRows,
                     String schemaLabel) {
}
