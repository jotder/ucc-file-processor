package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.inspector.BatchIngestStrategy.*;

/**
 * Union-mode execution for the streaming plugin-ingester path. Each batch member's records are
 * accumulated into per-member {@code raw_<KEY>_f<srcId>} tables; after all members are ingested,
 * each segment's per-member tables are unioned into one {@code raw_<KEY>} view, then transformed →
 * written → lineage-counted once for the whole batch. This amortises the fixed per-batch cost
 * across many small files and consolidates output rather than producing per-file fragments.
 *
 * <p>Selected by {@link StreamingPluginBatchStrategy} when no batch member meets
 * {@code processing.streaming.large_file_bytes}.
 */
final class UnionModeIngester {

    private static final Logger log = LoggerFactory.getLogger(StreamingPluginBatchStrategy.class);

    private UnionModeIngester() {}

    static IngestOutcome run(Batch batch, PipelineConfig cfg) {
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
            try (var conn = DuckDbUtil.openConnection(tempDb)) {
                configure(conn, cfg);

                // ── ingest every member into its own raw tables ──────────────────
                int memberIdx = 0;
                for (Batch.Member m : batch.members()) {
                    IngestProgress.track(cfg.identity().pipelineName(), batch.batchId(),
                            m.file().getName(), ++memberIdx, batch.members().size());
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

                        // Consolidate the per-member raw tables through a lazy UNION ALL
                        // *view* rather than copying every member's rows into a physical
                        // raw_<KEY> table first. The single transform below pulls the union
                        // through, so the batch is materialised once (transformed_<KEY>)
                        // instead of twice (raw_<KEY> + transformed_<KEY>) — peak scratch
                        // drops by ~1× the segment's data and the redundant copy is gone.
                        // Mirrors the CSV streaming-UNION path (CsvBatchStrategy). The member
                        // tables must outlive the transform (the view reads them), so they're
                        // dropped only after the write/lineage below.
                        String unionTable = "raw_" + segKey;
                        List<String> memberTables = new ArrayList<>();
                        Map<Integer, String> segSrcToFile = new LinkedHashMap<>();
                        for (Map.Entry<Integer, String> ce : contribs.entrySet()) {
                            memberTables.add(ce.getValue());   // already carries __src_id
                            segSrcToFile.put(ce.getKey(), srcIdToFile.get(ce.getKey()));
                        }
                        if (memberTables.isEmpty()) continue;

                        try (Statement st = conn.createStatement()) {
                            st.execute("CREATE VIEW \"" + unionTable + "\" AS " + unionAll(memberTables));
                        }

                        String destTable = "transformed_" + segKey;
                        DataTransformer.materialize(conn, segSchema, cfg, unionTable, destTable);

                        var written = writeAndTrace(conn, destTable, partitionColumns(segSchema),
                                cfg, Paths.get(cfg.dirs().database(), segKey).toString(),
                                consolidatedBaseName(survivors, batch),
                                batch.batchId(), segSrcToFile);

                        allOutputs.addAll(written.outputs());
                        allLineage.addAll(written.lineage());

                        dropView(conn, unionTable);
                        for (String mt : memberTables) dropTable(conn, mt);
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
