package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Processes one {@link Batch} in a single pass: ingests each member into a
 * per-file temp table, inserts accepted rows into a shared {@code raw_input}
 * tagged with {@code __src_id}, transforms once, writes consolidated partition
 * output, computes the lineage matrix, and commits (manifest → markers → backup
 * → audit). Rejected members are quarantined; their rows never reach
 * {@code raw_input}.
 */
public final class BatchProcessor {

    private BatchProcessor() {}

    public static void process(Batch batch, PipelineConfig cfg, BatchAuditWriter audit) {
        LocalDateTime batchStart = LocalDateTime.now();
        String batchStatus = "SUCCESS";
        String batchError  = "";

        Map<Integer, String> srcIdToFile = new LinkedHashMap<>();
        List<Batch.Member>   survivors   = new ArrayList<>();
        List<MemberAudit>    memberAudits = new ArrayList<>();
        long totalInputRows = 0;
        List<PartitionOutput> outputs = List.of();
        List<LineageRow>      lineage = List.of();

        File tempDb = null;
        try {
            tempDb = DuckDbUtil.tempDbFile("duckdb_batch_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                boolean rawCreated = false;
                for (Batch.Member m : batch.members()) {
                    LocalDateTime mStart = LocalDateTime.now();
                    String tempTable = "raw_f" + m.srcId();
                    IngestResult ing;
                    try {
                        ing = CsvIngester.ingest(m.file(), conn, m.selection().schema(), cfg, tempTable);
                    } catch (IOException e) {
                        QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_UNREADABLE",
                                msg(e), mStart));
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
                        // Empty-but-clean file: contributes nothing, not an error.
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
                    DataTransformer.materialize(conn, batch.members().get(0).selection().schema(), cfg);
                    String dbDir = (batch.table() != null && !batch.table().isBlank())
                            ? Paths.get(cfg.databaseDir, batch.table()).toString()
                            : cfg.databaseDir;
                    String baseName = survivors.size() == 1
                            ? CsvIngester.stripExtensions(survivors.get(0).file().getName())
                            : batch.batchId();
                    outputs = PartitionWriter.write(conn, "transformed", dbDir,
                            cfg.outputFormat, cfg.compression, baseName);
                    lineage = LineageCollector.collect(conn, "transformed",
                            batch.batchId(), srcIdToFile, outputs);
                }
            } // connection closed
        } catch (Exception e) {
            batchStatus = "FAILED";
            batchError  = msg(e);
            e.printStackTrace();
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        // ── commit / record ──────────────────────────────────────────────────
        try {
            if ("SUCCESS".equals(batchStatus)) {
                commit(batch, cfg, survivors, outputs, lineage);
            }
            // EMPTY and FAILED: no outputs revealed beyond what PartitionWriter wrote;
            // for FAILED we still leave survivors in the inbox (no markers, no backup).
            writeAudit(batch, cfg, audit, batchStart, batchStatus, batchError,
                    memberAudits, survivors, outputs, lineage, totalInputRows);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── commit: register, manifest, markers, backup ────────────────────────────

    private static void commit(Batch batch, PipelineConfig cfg, List<Batch.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage)
            throws IOException {

        DuckLakeRegistrar.register(outputs.stream().map(PartitionOutput::outputFile).toList(),
                batch.table(), cfg);

        Path poll   = Paths.get(cfg.pollDir).toAbsolutePath().normalize();
        Path backup = (cfg.backupDir != null && !cfg.backupDir.isBlank())
                ? Paths.get(cfg.backupDir).toAbsolutePath() : null;

        // Build manifest (computed paths) BEFORE creating markers / moving files.
        List<BatchManifest.MemberEntry> memberEntries = new ArrayList<>();
        List<String> markerPaths = new ArrayList<>();
        for (Batch.Member m : survivors) {
            Path filePath = m.file().toPath().toAbsolutePath().normalize();
            String rel    = poll.relativize(filePath).toString().replace('\\', '/');
            String backupPath = backup != null
                    ? backup.resolve(poll.relativize(filePath)).toString() : "";
            markerPaths.add(MarkerManager.getMarkerPath(m.file(), cfg).toString());
            memberEntries.add(new BatchManifest.MemberEntry(
                    m.file().getName(), m.srcId(), rel, backupPath, "SUCCESS"));
        }

        if (cfg.manifestsDir != null) {
            BatchManifest manifest = new BatchManifest();
            manifest.batchId     = batch.batchId();
            manifest.pipeline    = cfg.pipelineName;
            manifest.schemaName  = batch.schemaName();
            manifest.outputTable = batch.table();
            manifest.createdAt   = LocalDateTime.now().format(DuckDbUtil.DT_FMT);
            manifest.members     = memberEntries;
            manifest.outputs     = outputs.stream()
                    .map(o -> new BatchManifest.OutputEntry(o.partition(), o.outputFile())).toList();
            manifest.markers     = markerPaths;
            ManifestStore.write(cfg.manifestsDir, manifest);
        }

        for (Batch.Member m : survivors) MarkerManager.createMarkerFile(m.file(), cfg);
        if (backup != null)
            for (Batch.Member m : survivors) backupFile(m.file(), cfg);
    }

    /** Move a survivor source file into the backup dir, preserving poll-relative path. */
    private static void backupFile(File inputFile, PipelineConfig cfg) throws IOException {
        Path poll = Paths.get(cfg.pollDir).toAbsolutePath().normalize();
        Path file = inputFile.toPath().toAbsolutePath().normalize();
        Path dst  = Paths.get(cfg.backupDir).resolve(poll.relativize(file));
        Files.createDirectories(dst.getParent());
        Files.move(file, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    // ── audit assembly ──────────────────────────────────────────────────────────

    private static void writeAudit(Batch batch, PipelineConfig cfg, BatchAuditWriter audit,
                                   LocalDateTime batchStart, String batchStatus, String batchError,
                                   List<MemberAudit> memberAudits, List<Batch.Member> survivors,
                                   List<PartitionOutput> outputs, List<LineageRow> lineage,
                                   long totalInputRows) {
        if (audit == null) return;
        LocalDateTime end = LocalDateTime.now();

        // per-member output files (from lineage), keyed by srcId
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

        long totalOutputRows = lineage.stream().mapToLong(LineageRow::rowCount).sum();
        long totalOutputBytes = outputs.stream().mapToLong(PartitionOutput::bytes).sum();

        BatchAuditWriter.BatchRow batchRow = new BatchAuditWriter.BatchRow(
                batch.batchId(), cfg.pipelineName, batch.schemaName(), batch.table(),
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

    /** Internal per-member audit accumulator. */
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
