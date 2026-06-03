package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.inspector.BatchIngestStrategy.configure;
import static com.gamma.inspector.BatchIngestStrategy.dropTable;
import static com.gamma.inspector.BatchIngestStrategy.msg;
import static com.gamma.inspector.BatchIngestStrategy.openTempDb;

/**
 * The unified plugin-ingester engine. Every plugin ingests via the single {@link StreamingFileIngester}
 * SPI (record-by-record {@code emit}), and this strategy picks one of two execution modes per batch by
 * size — so the same ingester handles both pain points:
 *
 * <ul>
 *   <li><b>Generation mode</b> — when the batch's largest member is {@code >=
 *       processing.streaming.large_file_bytes}. Each member is streamed into a {@link DuckDbRecordSink}
 *       that flushes bounded "generations" to partitioned output as it goes, so a single
 *       multi-hundred-GB / TB file is processed with bounded heap <em>and</em> scratch. There is no
 *       cross-member union — each member writes its own per-generation output files.</li>
 *   <li><b>Union mode</b> — otherwise (the common many-small-files case). Each member's records are
 *       accumulated into a {@code raw_<KEY>_f<srcId>} table; after all members are ingested, each
 *       segment's per-member tables are unioned into one {@code raw_<KEY>}, then transformed → written →
 *       lineage-counted <em>once</em> for the whole batch. This amortises the fixed per-batch cost
 *       (one temp DB, one transform, one write) across thousands of files and consolidates output
 *       instead of exploding it into per-file fragments.</li>
 * </ul>
 *
 * <p>Quarantine/empty semantics are identical in both modes: an ingester {@code IOException}/decode
 * error quarantines the file as {@code QUARANTINED_UNREADABLE}; zero emitted rows quarantines as
 * {@code QUARANTINED_MISMATCH}. A framework-side flush failure ({@link SinkFlushException}) is not a
 * file fault, so it fails the batch instead of quarantining the input.
 */
final class StreamingPluginBatchStrategy implements BatchIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(StreamingPluginBatchStrategy.class);

    /** Default per-generation row budget when {@code processing.streaming.flush_records} is unset. */
    static final long DEFAULT_FLUSH_ROWS = 5_000_000L;

    /** {@code > 0} forces generation mode with this row budget (test seam); {@code <= 0} = config-driven. */
    private final long forcedFlushRows;

    /** Production: read flush budget from config and pick mode by member size. */
    StreamingPluginBatchStrategy() { this.forcedFlushRows = -1L; }

    /** Test seam: force generation mode with a small {@code flushRows} so a tiny input flushes repeatedly. */
    StreamingPluginBatchStrategy(long flushRows) { this.forcedFlushRows = flushRows; }

    @Override
    public IngestOutcome ingest(Batch batch, PipelineConfig cfg) {
        long threshold = cfg.processing().largeFileBytes();
        long maxMemberBytes = batch.members().stream().mapToLong(Batch.Member::bytes).max().orElse(0L);
        boolean generationMode = forcedFlushRows > 0 || (threshold > 0 && maxMemberBytes >= threshold);

        if (generationMode) {
            long flush = forcedFlushRows > 0 ? forcedFlushRows
                    : (cfg.processing().flushRecords() > 0 ? cfg.processing().flushRecords() : DEFAULT_FLUSH_ROWS);
            return ingestGeneration(batch, cfg, flush);
        }
        return ingestUnion(batch, cfg);
    }

    private StreamingFileIngester instantiate(PipelineConfig cfg) {
        try {
            return (StreamingFileIngester) Class.forName(cfg.schemas().ingesterClass())
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Cannot instantiate streaming ingester: "
                    + cfg.schemas().ingesterClass(), e);
        }
    }

    // ── generation mode (huge single files) ─────────────────────────────────────

    private IngestOutcome ingestGeneration(Batch batch, PipelineConfig cfg, long flushRows) {
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
            log.error("Batch {} failed during streaming (generation) processing", batch.batchId(), e);
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        String schemaNames = String.join(",", cfg.schemas().segments().keySet());
        return new IngestOutcome(batchStart, batchStatus, batchError, survivors, memberAudits,
                allOutputs, allLineage, totalInputRows, schemaNames);
    }

    // ── union mode (many small files) ────────────────────────────────────────────

    private IngestOutcome ingestUnion(Batch batch, PipelineConfig cfg) {
        LocalDateTime batchStart = LocalDateTime.now();
        String batchStatus = "SUCCESS";
        String batchError  = "";

        StreamingFileIngester ingester = instantiate(cfg);

        List<Batch.Member> survivors    = new ArrayList<>();
        List<MemberAudit>  memberAudits = new ArrayList<>();
        List<PartitionOutput> allOutputs = new ArrayList<>();
        List<LineageRow>      allLineage = new ArrayList<>();
        long totalInputRows = 0;

        Map<Integer, String> srcIdToFile = new LinkedHashMap<>();
        // segKey → (srcId → raw_<KEY>_f<srcId>) for members that contributed rows
        Map<String, Map<Integer, String>> tablesBySeg = new LinkedHashMap<>();
        for (String key : cfg.schemas().segments().keySet()) tablesBySeg.put(key, new LinkedHashMap<>());

        File tempDb = null;
        try {
            tempDb = openTempDb(cfg, "duckdb_stream_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                configure(conn, cfg);

                // ── ingest every member into its own raw tables ──────────────────
                for (Batch.Member m : batch.members()) {
                    LocalDateTime mStart = LocalDateTime.now();
                    String stem = CsvIngester.stripExtensions(m.file().getName());
                    long memberParsed = 0, memberErrors = 0;
                    Map<String, String> rawTables = Map.of();
                    boolean quarantined = false;

                    try (DuckDbRecordSink sink = new DuckDbRecordSink(
                            conn, m.srcId(), cfg, batch.batchId(), stem, m.file().getName(),
                            Long.MAX_VALUE, true)) {
                        try {
                            ingester.ingest(m.file(), sink, m.srcId(), cfg);
                            sink.finish();
                        } catch (SinkFlushException e) {
                            throw e;   // framework/schema fault → fail the batch
                        } catch (Exception e) {
                            QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                            memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_UNREADABLE", msg(e), mStart));
                            quarantined = true;
                        }
                        if (!quarantined) {
                            memberParsed = sink.parsedRows();
                            memberErrors = sink.errorRows();
                            rawTables    = sink.rawTables();
                        }
                    }
                    if (quarantined) continue;

                    if (memberParsed == 0) {
                        QuarantineManager.quarantine(m.file(), "field_mismatch", memberErrors > 0, cfg);
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_MISMATCH",
                                "0 valid rows across all segments", mStart));
                        for (String t : rawTables.values()) dropTable(conn, t);
                        continue;
                    }

                    survivors.add(m);
                    srcIdToFile.put(m.srcId(), m.file().getName());
                    totalInputRows += memberParsed;
                    memberAudits.add(MemberAudit.accepted(m, memberParsed, memberErrors, mStart));
                    for (Map.Entry<String, String> e : rawTables.entrySet())
                        tablesBySeg.get(e.getKey()).put(m.srcId(), e.getValue());
                }

                if (survivors.isEmpty()) {
                    batchStatus = "EMPTY";
                } else {
                    // ── union → transform → write → lineage per segment, once ─────
                    for (Map.Entry<String, Map<String, Object>> entry : cfg.schemas().segments().entrySet()) {
                        String              segKey    = entry.getKey();
                        Map<String, Object> segSchema = entry.getValue();
                        Map<Integer, String> contribs = tablesBySeg.get(segKey);
                        if (contribs == null || contribs.isEmpty()) continue;

                        String unionTable = "raw_" + segKey;
                        boolean unionCreated = false;
                        Map<Integer, String> segSrcToFile = new LinkedHashMap<>();
                        for (Map.Entry<Integer, String> ce : contribs.entrySet()) {
                            int    srcId       = ce.getKey();
                            String memberTable = ce.getValue();   // already carries __src_id
                            try (Statement st = conn.createStatement()) {
                                if (!unionCreated) {
                                    st.execute("CREATE TABLE \"" + unionTable + "\" AS SELECT * FROM \""
                                            + memberTable + "\" WHERE false");
                                    unionCreated = true;
                                }
                                st.execute("INSERT INTO \"" + unionTable + "\" SELECT * FROM \""
                                        + memberTable + "\"");
                            }
                            dropTable(conn, memberTable);
                            segSrcToFile.put(srcId, srcIdToFile.get(srcId));
                        }
                        if (!unionCreated) continue;

                        String destTable = "transformed_" + segKey;
                        DataTransformer.materialize(conn, segSchema, cfg, unionTable, destTable);

                        List<PartitionDef> partDefs = PartitionDef.fromSchema(segSchema);
                        List<String> partCols = partDefs.isEmpty()
                                ? List.of("year", "month", "day")
                                : PartitionDef.columnNames(partDefs);

                        String dbDir = Paths.get(cfg.dirs().database(), segKey).toString();
                        String baseName = survivors.size() == 1
                                ? CsvIngester.stripExtensions(survivors.get(0).file().getName())
                                : batch.batchId();

                        List<PartitionOutput> segOutputs = PartitionWriter.write(
                                conn, destTable, dbDir, cfg.output().format(),
                                cfg.output().compression(), baseName, partCols);
                        List<LineageRow> segLineage = LineageCollector.collect(
                                conn, destTable, batch.batchId(), segSrcToFile, segOutputs, partCols);

                        allOutputs.addAll(segOutputs);
                        allLineage.addAll(segLineage);

                        dropTable(conn, unionTable);
                        dropTable(conn, destTable);
                    }
                }
            }
        } catch (Exception e) {
            batchStatus = "FAILED";
            batchError  = msg(e);
            log.error("Batch {} failed during streaming (union) processing", batch.batchId(), e);
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        String schemaNames = String.join(",", cfg.schemas().segments().keySet());
        return new IngestOutcome(batchStart, batchStatus, batchError, survivors, memberAudits,
                allOutputs, allLineage, totalInputRows, schemaNames);
    }
}
