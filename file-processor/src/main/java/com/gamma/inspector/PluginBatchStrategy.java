package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
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
 * Plugin-ingester path. A {@link FileIngester} (named by {@link PipelineConfig.Schemas#ingesterClass()})
 * splits each member into one DuckDB table per segment key (event type). After all members
 * are ingested, each segment's tables are unioned across members, then transformed → written →
 * lineage-counted independently. All segment outputs and lineage rows aggregate into one batch.
 *
 * <p>Behaviour-identical to the former {@code BatchProcessor.processPlugin} — only the
 * commit/audit tail was lifted out into {@link BatchProcessor}.
 */
final class PluginBatchStrategy implements BatchIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(PluginBatchStrategy.class);

    @SuppressWarnings("unchecked")
    @Override
    public IngestOutcome ingest(Batch batch, PipelineConfig cfg) {
        LocalDateTime batchStart = LocalDateTime.now();
        String batchStatus = "SUCCESS";
        String batchError  = "";

        FileIngester ingester;
        try {
            ingester = (FileIngester) Class.forName(cfg.schemas().ingesterClass())
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Cannot instantiate ingester: " + cfg.schemas().ingesterClass(), e);
        }

        // Per-member audit and survivor tracking
        Map<Integer, String>   srcIdToFile  = new LinkedHashMap<>();
        List<Batch.Member>     survivors    = new ArrayList<>();
        List<MemberAudit>      memberAudits = new ArrayList<>();
        long totalInputRows = 0;

        // Per-segment: track which members contributed rows
        // key → { srcId → List<FileIngester.Segment> }
        Map<String, Map<Integer, FileIngester.Segment>> segmentsBySrcId = new LinkedHashMap<>();
        for (String key : cfg.schemas().segments().keySet()) segmentsBySrcId.put(key, new LinkedHashMap<>());

        List<PartitionOutput> allOutputs = new ArrayList<>();
        List<LineageRow>      allLineage = new ArrayList<>();

        File tempDb = null;
        try {
            tempDb = openTempDb(cfg, "duckdb_plugin_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                configure(conn, cfg);

                // ── ingest all members ────────────────────────────────────────
                for (Batch.Member m : batch.members()) {
                    LocalDateTime mStart = LocalDateTime.now();
                    List<FileIngester.Segment> segs;
                    try {
                        segs = ingester.ingest(m.file(), conn, m.srcId(), cfg);
                    } catch (IOException e) {
                        QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_UNREADABLE", msg(e), mStart));
                        continue;
                    } catch (Exception e) {
                        QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_UNREADABLE", msg(e), mStart));
                        continue;
                    }

                    long memberParsed = segs.stream().mapToLong(s -> s.stats().parsedRows()).sum();
                    long memberErrors = segs.stream().mapToLong(s -> s.stats().errorRows()).sum();

                    if (memberParsed == 0) {
                        QuarantineManager.quarantine(m.file(), "field_mismatch", memberErrors > 0, cfg);
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_MISMATCH",
                                "0 valid rows across all segments", mStart));
                        // Drop any empty tables the ingester created
                        for (FileIngester.Segment s : segs) dropTable(conn, s.rawTable());
                        continue;
                    }

                    // Register survivor
                    srcIdToFile.put(m.srcId(), m.file().getName());
                    survivors.add(m);
                    totalInputRows += memberParsed;
                    memberAudits.add(MemberAudit.accepted(m, memberParsed, memberErrors, mStart));

                    // Index each segment by its key
                    for (FileIngester.Segment s : segs) {
                        if (segmentsBySrcId.containsKey(s.key())) {
                            segmentsBySrcId.get(s.key()).put(m.srcId(), s);
                        }
                    }
                }

                if (survivors.isEmpty()) {
                    batchStatus = "EMPTY";
                } else {
                    // ── transform + write per segment ─────────────────────────
                    for (Map.Entry<String, Map<String, Object>> entry : cfg.schemas().segments().entrySet()) {
                        String              segKey    = entry.getKey();
                        Map<String, Object> segSchema = entry.getValue();
                        Map<Integer, FileIngester.Segment> contribs = segmentsBySrcId.get(segKey);

                        if (contribs == null || contribs.isEmpty()) continue;

                        // Union segment tables from each contributing member into "raw_{KEY}"
                        String unionTable = "raw_" + segKey;
                        boolean unionCreated = false;
                        Map<Integer, String> segSrcToFile = new LinkedHashMap<>();
                        for (Map.Entry<Integer, FileIngester.Segment> ce : contribs.entrySet()) {
                            int                  srcId    = ce.getKey();
                            FileIngester.Segment seg      = ce.getValue();
                            if (seg.stats().parsedRows() == 0) {
                                dropTable(conn, seg.rawTable());
                                continue;
                            }
                            try (Statement st = conn.createStatement()) {
                                if (!unionCreated) {
                                    st.execute("CREATE TABLE \"" + unionTable
                                            + "\" AS SELECT *, CAST(" + srcId
                                            + " AS INTEGER) AS __src_id FROM \""
                                            + seg.rawTable() + "\" WHERE false");
                                    unionCreated = true;
                                }
                                st.execute("INSERT INTO \"" + unionTable + "\" SELECT *, " + srcId
                                        + " FROM \"" + seg.rawTable() + "\"");
                            }
                            dropTable(conn, seg.rawTable());
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
            log.error("Batch {} failed during plugin processing", batch.batchId(), e);
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        // Use all segment keys as the audit schema label.
        String schemaNames = String.join(",", cfg.schemas().segments().keySet());
        return new IngestOutcome(batchStart, batchStatus, batchError, survivors, memberAudits,
                allOutputs, allLineage, totalInputRows, schemaNames);
    }
}
