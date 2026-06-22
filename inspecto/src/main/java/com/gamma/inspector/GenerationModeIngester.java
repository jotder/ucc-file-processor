package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.gamma.inspector.BatchIngestStrategy.*;

/**
 * Generation-mode execution for the streaming plugin-ingester path. Each batch member is streamed
 * into a {@link DuckDbRecordSink} that flushes bounded generations to partitioned output as it goes,
 * so a single huge file is processed with bounded heap and scratch. No cross-member union is
 * performed — each member writes its own per-generation output files.
 *
 * <p>Selected by {@link StreamingPluginBatchStrategy} when the largest batch member meets or
 * exceeds {@code processing.streaming.large_file_bytes}.
 */
final class GenerationModeIngester {

    private static final Logger log = LoggerFactory.getLogger(StreamingPluginBatchStrategy.class);

    private GenerationModeIngester() {}

    static IngestOutcome run(Batch batch, PipelineConfig cfg, long flushRows) {
        LocalDateTime batchStart = LocalDateTime.now();
        String batchStatus = "SUCCESS";
        String batchError  = "";

        StreamingFileIngester ingester = instantiate(cfg);

        List<Batch.Member> survivors    = new ArrayList<>();
        List<MemberAudit>  memberAudits = new ArrayList<>();
        List<PartitionOutput> allOutputs = new ArrayList<>();
        List<LineageRow>      allLineage = new ArrayList<>();
        long totalInputRows = 0;

        File tempDb = null;
        try {
            tempDb = openTempDb(cfg, "duckdb_stream_");
            try (var conn = DuckDbUtil.openConnection(tempDb)) {
                configure(conn, cfg);

                int memberIdx = 0;
                for (Batch.Member m : batch.members()) {
                    IngestProgress.track(cfg.identity().pipelineName(), batch.batchId(),
                            m.file().getName(), ++memberIdx, batch.members().size());
                    LocalDateTime mStart = LocalDateTime.now();
                    String stem = CsvIngester.stripExtensions(m.file().getName());
                    try (DuckDbRecordSink sink = new DuckDbRecordSink(
                            conn, m.srcId(), cfg, batch.batchId(), stem, m.file().getName(), flushRows)) {
                        try {
                            ingester.ingest(m.file(), sink, m.srcId(), cfg);
                            sink.finish();
                        } catch (SinkFlushException e) {
                            throw e;   // framework/schema fault → fail the batch (don't quarantine)
                        } catch (Exception e) {
                            QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                            memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_UNREADABLE", msg(e), mStart));
                            continue;
                        }

                        long memberParsed = sink.parsedRows();
                        long memberErrors = sink.errorRows();
                        if (memberParsed == 0) {
                            QuarantineManager.quarantine(m.file(), "field_mismatch", memberErrors > 0, cfg);
                            memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_MISMATCH",
                                    "0 valid rows across all segments", mStart));
                            continue;
                        }

                        survivors.add(m);
                        totalInputRows += memberParsed;
                        allOutputs.addAll(sink.outputs());
                        allLineage.addAll(sink.lineage());
                        memberAudits.add(MemberAudit.accepted(m, memberParsed, memberErrors, mStart));
                        log.info("[INGEST] [{}] streamed {} row(s) → {} output file(s){}",
                                m.file().getName(), String.format("%,d", memberParsed),
                                sink.outputs().size(),
                                memberErrors > 0 ? "  rejected=" + memberErrors : "");
                    }
                }

                if (survivors.isEmpty()) batchStatus = "EMPTY";
            }
        } catch (Exception e) {
            batchStatus = "FAILED";
            batchError  = msg(e);
            log.error("Batch {} failed during streaming (generation) processing", batch.batchId(), e);
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        String schemaNames = String.join(",", cfg.schemas().segments().keySet());
        return new IngestOutcome(batchStart, batchStatus, batchError, survivors, memberAudits,
                allOutputs, allLineage, totalInputRows, schemaNames);
    }

    private static StreamingFileIngester instantiate(PipelineConfig cfg) {
        try {
            return (StreamingFileIngester) Class.forName(cfg.schemas().ingesterClass())
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Cannot instantiate streaming ingester: "
                    + cfg.schemas().ingesterClass(), e);
        }
    }
}
