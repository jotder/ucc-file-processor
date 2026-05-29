package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Processes one {@link Batch} in a single pass.
 *
 * <h3>CSV path (default)</h3>
 * Ingests each member into a per-file temp table, inserts accepted rows into a shared
 * {@code raw_input} tagged with {@code __src_id}, transforms once, writes consolidated
 * partition output, computes the lineage matrix, and commits (manifest → markers →
 * backup → audit). Rejected members are quarantined; their rows never reach
 * {@code raw_input}.
 *
 * <h3>Plugin-ingester path</h3>
 * When {@link PipelineConfig#ingesterClass} is set, a {@link FileIngester} implementation
 * is instantiated and called for each member. The ingester populates one DuckDB table per
 * segment key (event type). After all members are ingested, the processor unions each
 * segment's tables across members, then runs an independent transform → write → lineage
 * pass per segment. All segment outputs and lineage rows are aggregated into a single
 * {@code BatchRow} audit entry.
 */
public final class BatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);

    private BatchProcessor() {}

    // ── entry point ───────────────────────────────────────────────────────────

    public static void process(Batch batch, PipelineConfig cfg, BatchAuditWriter audit) {
        if (cfg.schemas().ingesterClass() != null) {
            processPlugin(batch, cfg, audit);
        } else {
            processCsv(batch, cfg, audit);
        }
    }

    // ── CSV path ──────────────────────────────────────────────────────────────

    private static void processCsv(Batch batch, PipelineConfig cfg, BatchAuditWriter audit) {
        LocalDateTime batchStart = LocalDateTime.now();
        String batchStatus = "SUCCESS";
        String batchError  = "";

        Map<Integer, String> srcIdToFile  = new LinkedHashMap<>();
        List<Batch.Member>   survivors    = new ArrayList<>();
        List<MemberAudit>    memberAudits = new ArrayList<>();
        long totalInputRows = 0;
        List<PartitionOutput> outputs = List.of();
        List<LineageRow>      lineage = List.of();

        File tempDb = null;
        try {
            tempDb = DuckDbUtil.tempDbFile("duckdb_batch_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                DuckDbUtil.applyWorkerThreads(conn, cfg.processing().duckdbThreads());
                boolean rawCreated = false;
                for (Batch.Member m : batch.members()) {
                    LocalDateTime mStart = LocalDateTime.now();
                    String tempTable = "raw_f" + m.srcId();
                    IngestResult ing;
                    try {
                        ing = DuckDbCsvIngester.usesDuckDb(cfg)
                                ? DuckDbCsvIngester.ingest(m.file(), conn, m.selection().schema(), cfg, tempTable)
                                : CsvIngester.ingest(m.file(), conn, m.selection().schema(), cfg, tempTable);
                    } catch (IOException e) {
                        QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_UNREADABLE", msg(e), mStart));
                        dropTable(conn, tempTable);
                        continue;
                    }

                    if (ing.parsedRows() == 0
                            && (ing.errorRows() > 0 || ing.junkCandidateRows() > 0)) {
                        QuarantineManager.quarantine(m.file(), "field_mismatch",
                                ing.errorRows() > 0, cfg);
                        String reason = ing.errorRows() > 0
                                ? String.format("0 valid rows; %d row(s) rejected (field mismatch)", ing.errorRows())
                                : String.format("0 valid rows; %d content line(s) failed column-count", ing.junkCandidateRows());
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_MISMATCH", reason, mStart));
                        dropTable(conn, tempTable);
                        continue;
                    }

                    if (ing.parsedRows() == 0) {
                        memberAudits.add(MemberAudit.accepted(m, 0, 0, mStart));
                        dropTable(conn, tempTable);
                        continue;
                    }

                    try (Statement st = conn.createStatement()) {
                        if (!rawCreated) {
                            st.execute("CREATE TABLE raw_input AS SELECT *, CAST(" + m.srcId()
                                    + " AS INTEGER) AS __src_id FROM \"" + tempTable + "\" WHERE false");
                            rawCreated = true;
                        }
                        st.execute("INSERT INTO raw_input SELECT *, " + m.srcId()
                                + " FROM \"" + tempTable + "\"");
                    }
                    dropTable(conn, tempTable);

                    srcIdToFile.put(m.srcId(), m.file().getName());
                    survivors.add(m);
                    totalInputRows += ing.parsedRows();
                    memberAudits.add(MemberAudit.accepted(m, ing.parsedRows(), ing.errorRows(), mStart));
                }

                if (!rawCreated) {
                    batchStatus = "EMPTY";
                } else {
                    Map<String, Object> schema = batch.members().get(0).selection().schema();
                    DataTransformer.materialize(conn, schema, cfg);

                    List<PartitionDef> partDefs = PartitionDef.fromSchema(schema);
                    List<String> partCols = partDefs.isEmpty()
                            ? List.of("year", "month", "day")
                            : PartitionDef.columnNames(partDefs);

                    String dbDir = (batch.table() != null && !batch.table().isBlank())
                            ? Paths.get(cfg.dirs().database(), batch.table()).toString()
                            : cfg.dirs().database();
                    String baseName = survivors.size() == 1
                            ? CsvIngester.stripExtensions(survivors.get(0).file().getName())
                            : batch.batchId();
                    outputs = PartitionWriter.write(conn, "transformed", dbDir,
                            cfg.output().format(), cfg.output().compression(), baseName, partCols);
                    lineage = LineageCollector.collect(conn, "transformed",
                            batch.batchId(), srcIdToFile, outputs, partCols);
                }
            }
        } catch (Exception e) {
            batchStatus = "FAILED";
            batchError  = msg(e);
            log.error("Batch {} failed during CSV processing", batch.batchId(), e);
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        try {
            if ("SUCCESS".equals(batchStatus))
                commit(batch, cfg, survivors, outputs, lineage);
            writeAudit(batch, cfg, audit, batchStart, batchStatus, batchError,
                    memberAudits, survivors, outputs, lineage, totalInputRows);
        } catch (Exception e) {
            log.error("Batch {} failed during commit/audit", batch.batchId(), e);
        }
    }

    // ── plugin-ingester path ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void processPlugin(Batch batch, PipelineConfig cfg, BatchAuditWriter audit) {
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
            tempDb = DuckDbUtil.tempDbFile("duckdb_plugin_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                DuckDbUtil.applyWorkerThreads(conn, cfg.processing().duckdbThreads());

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

        try {
            if ("SUCCESS".equals(batchStatus))
                commit(batch, cfg, survivors, allOutputs, allLineage);
            // Use first segment key as schemaName for audit; all keys as outputTable
            String schemaNames = String.join(",", cfg.schemas().segments().keySet());
            writeAuditPlugin(batch, cfg, audit, batchStart, batchStatus, batchError,
                    memberAudits, survivors, allOutputs, allLineage, totalInputRows, schemaNames);
        } catch (Exception e) {
            log.error("Batch {} failed during commit/audit", batch.batchId(), e);
        }
    }

    // ── commit: register, manifest, markers, backup ────────────────────────────

    private static void commit(Batch batch, PipelineConfig cfg, List<Batch.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage)
            throws IOException {

        // ── ordering rationale ────────────────────────────────────────────────
        // Markers signal "already processed; skip on next poll." If a crash leaves
        // markers without a corresponding backup, the input file is stranded in the
        // inbox forever (skipped by poll, never moved). So markers go LAST, after
        // every other side effect is durable. Sequence:
        //   1. DuckLake register (optional, non-fatal — log & continue)
        //   2. Manifest write   (required: reprocess reads from here)
        //   3. Backup originals (moves files out of the inbox)
        //   4. Marker files     (last — only created when 1-3 all succeeded)
        // A crash at any point before step 4 is idempotent on rerun: outputs use
        // OVERWRITE_OR_IGNORE, the manifest is rewritten, and absent markers mean
        // the (still-present-in-inbox) files are picked up again.

        DuckLakeRegistrar.register(outputs.stream().map(PartitionOutput::outputFile).toList(),
                batch.table(), cfg);

        Path poll   = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path backup = (cfg.dirs().backup() != null && !cfg.dirs().backup().isBlank())
                ? Paths.get(cfg.dirs().backup()).toAbsolutePath() : null;

        List<BatchManifest.MemberEntry> memberEntries = new ArrayList<>();
        List<String> markerPaths = new ArrayList<>();
        for (Batch.Member m : survivors) {
            Path filePath = m.file().toPath().toAbsolutePath().normalize();
            String rel    = poll.relativize(filePath).toString().replace('\\', '/');
            String backupPath = backup != null
                    ? backup.resolve(poll.relativize(filePath)).toString() : "";
            if (cfg.dirs().markers() != null)
                markerPaths.add(MarkerManager.getMarkerPath(m.file(), cfg).toString());
            memberEntries.add(new BatchManifest.MemberEntry(
                    m.file().getName(), m.srcId(), rel, backupPath, "SUCCESS"));
        }

        if (cfg.dirs().manifestsDir() != null) {
            BatchManifest manifest = new BatchManifest();
            manifest.batchId     = batch.batchId();
            manifest.pipeline    = cfg.identity().pipelineName();
            manifest.schemaName  = batch.schemaName();
            manifest.outputTable = batch.table();
            manifest.createdAt   = LocalDateTime.now().format(DuckDbUtil.DT_FMT);
            manifest.members     = memberEntries;
            manifest.outputs     = outputs.stream()
                    .map(o -> new BatchManifest.OutputEntry(o.partition(), o.outputFile())).toList();
            manifest.markers     = markerPaths;
            ManifestStore.write(cfg.dirs().manifestsDir(), manifest);
        }

        // Backup BEFORE markers — see ordering rationale at top of method.
        if (backup != null)
            for (Batch.Member m : survivors) backupFile(m.file(), cfg);

        // Markers LAST — created only after every other side-effect is durable.
        if (cfg.dirs().markers() != null)
            for (Batch.Member m : survivors) MarkerManager.createMarkerFile(m.file(), cfg);
    }

    private static void backupFile(File inputFile, PipelineConfig cfg) throws IOException {
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path file = inputFile.toPath().toAbsolutePath().normalize();
        Path dst  = Paths.get(cfg.dirs().backup()).resolve(poll.relativize(file));
        Files.createDirectories(dst.getParent());
        Files.move(file, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    // ── audit assembly ──────────────────────────────────────────────────────────

    private static void writeAudit(Batch batch, PipelineConfig cfg, BatchAuditWriter audit,
                                   LocalDateTime batchStart, String batchStatus, String batchError,
                                   List<MemberAudit> memberAudits, List<Batch.Member> survivors,
                                   List<PartitionOutput> outputs, List<LineageRow> lineage,
                                   long totalInputRows) {
        writeAuditPlugin(batch, cfg, audit, batchStart, batchStatus, batchError,
                memberAudits, survivors, outputs, lineage, totalInputRows, batch.schemaName());
    }

    private static void writeAuditPlugin(Batch batch, PipelineConfig cfg, BatchAuditWriter audit,
                                         LocalDateTime batchStart, String batchStatus, String batchError,
                                         List<MemberAudit> memberAudits, List<Batch.Member> survivors,
                                         List<PartitionOutput> outputs, List<LineageRow> lineage,
                                         long totalInputRows, String schemaLabel) {
        if (audit == null) return;
        LocalDateTime end = LocalDateTime.now();

        Map<Integer, LinkedHashSet<String>> outBySrc = new HashMap<>();
        for (LineageRow r : lineage)
            outBySrc.computeIfAbsent(r.srcId(), k -> new LinkedHashSet<>()).add(r.outputFile());

        List<BatchAuditWriter.FileRow> fileRows = new ArrayList<>();
        int rejected = 0;
        for (MemberAudit ma : memberAudits) {
            if (!ma.status().equals("SUCCESS")) rejected++;
            List<String> paths = new ArrayList<>(
                    outBySrc.getOrDefault(ma.srcId(), new LinkedHashSet<>()));
            fileRows.add(new BatchAuditWriter.FileRow(
                    ma.start().format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT),
                    ma.filename(), ma.status(), ma.parsedRows(), ma.errorRows(),
                    paths, Collections.nCopies(paths.size(), 0L),
                    Duration.between(ma.start(), end).toMillis(), ma.error(), batch.batchId()));
        }

        long totalOutputRows  = lineage.stream().mapToLong(LineageRow::rowCount).sum();
        long totalOutputBytes = outputs.stream().mapToLong(PartitionOutput::bytes).sum();

        BatchAuditWriter.BatchRow batchRow = new BatchAuditWriter.BatchRow(
                batch.batchId(), cfg.identity().pipelineName(), schemaLabel, batch.table(),
                batchStart.format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT), batchStatus,
                batch.members().size(), rejected, totalInputRows, totalOutputRows,
                outputs.size(), totalOutputBytes,
                Duration.between(batchStart, end).toMillis(), batchError);

        audit.flush(batchRow, fileRows, lineage);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void dropTable(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + table + "\"");
        } catch (Exception ignored) { }
    }

    private static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private record MemberAudit(int srcId, String filename, String status,
                                long parsedRows, long errorRows, String error, LocalDateTime start) {
        static MemberAudit accepted(Batch.Member m, long parsed, long errors, LocalDateTime start) {
            return new MemberAudit(m.srcId(), m.file().getName(), "SUCCESS", parsed, errors, "", start);
        }
        static MemberAudit rejected(Batch.Member m, String status, String error, LocalDateTime start) {
            return new MemberAudit(m.srcId(), m.file().getName(), status, 0, 0, error, start);
        }
    }
}
