package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.gamma.inspector.BatchIngestStrategy.configure;
import static com.gamma.inspector.BatchIngestStrategy.msg;
import static com.gamma.inspector.BatchIngestStrategy.openTempDb;

/**
 * Streaming plugin-ingester path. When {@link PipelineConfig.Schemas#ingesterClass()} resolves to a
 * {@link StreamingFileIngester}, each member is decoded record-by-record into a {@link
 * DuckDbRecordSink}, which flushes bounded "generations" to partitioned output as it goes — so a
 * single multi-hundred-GB / TB custom file (binary, ASN.1, …) is processed with bounded heap and
 * scratch, which the classic whole-file {@link PluginBatchStrategy} cannot do.
 *
 * <p>Unlike the classic path there is no cross-member union: each member streams independently and
 * writes its own per-generation output files (valid Hive layout, same trade-off as the CSV chunker).
 * Quarantine/empty semantics match the classic path — an ingester {@code IOException}/decode error
 * quarantines the file as {@code QUARANTINED_UNREADABLE}; zero emitted rows quarantines as
 * {@code QUARANTINED_MISMATCH}. A framework-side flush failure ({@link SinkFlushException}) is not a
 * file fault, so it fails the batch instead of quarantining the input.
 */
final class StreamingPluginBatchStrategy implements BatchIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(StreamingPluginBatchStrategy.class);

    /**
     * Default per-generation row budget. Bounds the scratch a single generation's raw + transformed
     * tables occupy; files smaller than this produce exactly one generation (output identical to a
     * single write). Tunable exposure via config is a deliberate follow-up (see design-notes D9).
     */
    static final long DEFAULT_FLUSH_ROWS = 5_000_000L;

    private final long flushRows;

    StreamingPluginBatchStrategy() { this(DEFAULT_FLUSH_ROWS); }

    /** Test seam: a small {@code flushRows} forces multiple generations on a tiny input. */
    StreamingPluginBatchStrategy(long flushRows) { this.flushRows = flushRows; }

    @Override
    public IngestOutcome ingest(Batch batch, PipelineConfig cfg) {
        LocalDateTime batchStart = LocalDateTime.now();
        String batchStatus = "SUCCESS";
        String batchError  = "";

        StreamingFileIngester ingester;
        try {
            ingester = (StreamingFileIngester) Class.forName(cfg.schemas().ingesterClass())
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Cannot instantiate streaming ingester: "
                    + cfg.schemas().ingesterClass(), e);
        }

        List<Batch.Member> survivors    = new ArrayList<>();
        List<MemberAudit>  memberAudits = new ArrayList<>();
        List<PartitionOutput> allOutputs = new ArrayList<>();
        List<LineageRow>      allLineage = new ArrayList<>();
        long totalInputRows = 0;

        File tempDb = null;
        try {
            tempDb = openTempDb(cfg, "duckdb_stream_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                configure(conn, cfg);

                for (Batch.Member m : batch.members()) {
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
            log.error("Batch {} failed during streaming plugin processing", batch.batchId(), e);
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        String schemaNames = String.join(",", cfg.schemas().segments().keySet());
        return new IngestOutcome(batchStart, batchStatus, batchError, survivors, memberAudits,
                allOutputs, allLineage, totalInputRows, schemaNames);
    }
}
