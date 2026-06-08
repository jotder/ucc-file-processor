package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static com.gamma.inspector.BatchIngestStrategy.scratchDir;

/**
 * Built-in CSV ingest path. Tags every accepted row with {@code __src_id}, transforms once, writes
 * consolidated partition output, and computes the lineage matrix; rejected members are quarantined
 * and their rows never reach the transform.
 *
 * <p>How rows reach the {@code transformed} table depends on the parse engine:
 * <ul>
 *   <li><b>Native {@code read_csv} engine</b> — fully streaming, no intermediate data copies. A
 *       single-member batch streams {@code read_csv → transform → COPY} in one pass
 *       ({@link #streamingIngest}, chunked for huge files via {@link #chunkedIngest}); a multi-member
 *       batch builds a lazy {@code read_csv} view per member and {@code UNION ALL}s them into one
 *       transform ({@link #unionStreamingIngest}). The data is materialised exactly once.</li>
 *   <li><b>Java parse engine</b> — each member is parsed into a per-file temp table and its accepted
 *       rows inserted into a shared {@code raw_input} table before the single transform (the loop in
 *       {@link #ingest}), since the line-by-line parser cannot stream through a view.</li>
 * </ul>
 *
 * <p>Behaviour-identical to the former {@code BatchProcessor.processCsv} — only the
 * commit/audit tail was lifted out into {@link BatchProcessor}.
 */
final class CsvBatchStrategy implements BatchIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(CsvBatchStrategy.class);

    @Override
    public IngestOutcome ingest(Batch batch, PipelineConfig cfg) {
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
            tempDb = openTempDb(cfg, "duckdb_batch_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                configure(conn, cfg);

                // Native (read_csv) batches stream with NO per-member raw_f/raw_input table copies:
                //   • single member → one streaming pass (read_csv → transform → COPY), chunked if
                //     the file exceeds the chunking threshold so peak scratch stays bounded;
                //   • many members  → each member becomes a lazy read_csv view, the views are
                //     UNION ALL-ed into one raw_input view, and a single transform pulls them
                //     through — the data is materialised exactly once (the transformed table)
                //     instead of the read_csv → raw_f<id> → raw_input → transformed triple-copy.
                // The Java parse engine keeps the per-member materialise→raw_input path below.
                if (DuckDbCsvIngester.decideNative(batch, cfg)) {
                    if (batch.members().size() == 1) {
                        Batch.Member only = batch.members().get(0);
                        return cfg.chunking().appliesTo(only.file().length())
                                ? chunkedIngest(batch, only, cfg, conn, batchStart)
                                : streamingIngest(batch, only, cfg, conn, batchStart);
                    }
                    return unionStreamingIngest(batch, cfg, conn, batchStart);
                }

                boolean rawCreated = false;
                for (Batch.Member m : batch.members()) {
                    LocalDateTime mStart = LocalDateTime.now();
                    String tempTable = "raw_f" + m.srcId();
                    IngestResult ing;
                    try {
                        // Reached only when decideNative() chose the Java path for this batch.
                        ing = CsvIngester.ingest(m.file(), conn, m.selection().schema(), cfg, tempTable);
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

        return new IngestOutcome(batchStart, batchStatus, batchError, survivors, memberAudits,
                outputs, lineage, totalInputRows, batch.schemaName());
    }

    /**
     * Multi-member native ({@code read_csv}) ingest with no per-member {@code raw_f}/{@code raw_input}
     * table copies. Each surviving member becomes a lazy {@code read_csv} view (via
     * {@link DuckDbCsvIngester#createRawInputView}); the views are {@code UNION ALL}-ed into a single
     * {@code raw_input} view that one {@code CREATE TABLE transformed AS …} pulls through in a single
     * streaming pass — so the batch's data is materialised exactly <em>once</em> (the
     * {@code transformed} table), versus the {@code read_csv → raw_f<id> → raw_input → transformed}
     * triple materialisation the Java-engine path uses.
     *
     * <p>Per-member quarantine is preserved exactly. Each member is probed with a {@code COUNT(*)}
     * over its view (which drives {@code read_csv} and fires {@code store_rejects}), so:
     * <ul>
     *   <li>an unreadable/undecodable file throws → quarantined {@code UNREADABLE} individually;</li>
     *   <li>0 valid rows with rejects → quarantined {@code MISMATCH};</li>
     *   <li>0 valid rows, no rejects → accepted with 0 rows but <em>not</em> a survivor;</li>
     *   <li>parsed &gt; 0 → survivor, feeds the union.</li>
     * </ul>
     * Only surviving members feed the union, so one bad file never fails the whole batch — identical
     * to the per-member loop it replaces, with the same {@code baseName} rule (single survivor keeps
     * its file stem; otherwise the consolidated output is named by batch id).
     */
    private IngestOutcome unionStreamingIngest(Batch batch, PipelineConfig cfg,
                                               Connection conn, LocalDateTime batchStart) throws Exception {
        Map<String, Object>  schema       = batch.members().get(0).selection().schema();
        Map<Integer, String> srcIdToFile  = new LinkedHashMap<>();
        List<Batch.Member>   survivors    = new ArrayList<>();
        List<MemberAudit>    memberAudits = new ArrayList<>();
        List<String>         memberViews  = new ArrayList<>();
        long totalInputRows = 0;

        for (Batch.Member m : batch.members()) {
            LocalDateTime mStart = LocalDateTime.now();
            String view = "raw_m" + m.srcId();

            long parsed;
            try {
                DuckDbCsvIngester.createRawInputView(m.file(), conn, schema, cfg, view, m.srcId());
                parsed = countRows(conn, view);   // drives read_csv (store_rejects fires here)
            } catch (Exception e) {
                QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_UNREADABLE", msg(e), mStart));
                dropView(conn, view);
                continue;
            }

            long rejects = DuckDbCsvIngester.drainRejects(conn, m.file(), cfg);

            if (parsed == 0 && rejects > 0) {
                QuarantineManager.quarantine(m.file(), "field_mismatch", true, cfg);
                String reason = String.format("0 valid rows; %d row(s) rejected (field mismatch)", rejects);
                memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_MISMATCH", reason, mStart));
                dropView(conn, view);
                continue;
            }
            if (parsed == 0) {   // genuinely empty file: accepted-0, not a survivor (mirrors the loop)
                memberAudits.add(MemberAudit.accepted(m, 0, 0, mStart));
                dropView(conn, view);
                continue;
            }

            srcIdToFile.put(m.srcId(), m.file().getName());
            survivors.add(m);
            memberViews.add(view);
            totalInputRows += parsed;
            memberAudits.add(MemberAudit.accepted(m, parsed, rejects, mStart));
        }

        if (survivors.isEmpty())
            return new IngestOutcome(batchStart, "EMPTY", "", List.of(), memberAudits,
                    List.of(), List.of(), 0, batch.schemaName());

        // UNION ALL the surviving member views into one raw_input view; transform pulls it through once.
        StringBuilder union = new StringBuilder();
        for (int i = 0; i < memberViews.size(); i++) {
            if (i > 0) union.append(" UNION ALL ");
            union.append("SELECT * FROM \"").append(memberViews.get(i)).append('"');
        }
        try (Statement st = conn.createStatement()) {
            st.execute("DROP VIEW IF EXISTS \"raw_input\"");
            st.execute("CREATE VIEW \"raw_input\" AS " + union);
        }
        DataTransformer.materialize(conn, schema, cfg);   // single streaming pass over all members

        List<String> partCols = partitionColumns(schema);
        String dbDir    = databaseDir(batch, cfg);
        String baseName = survivors.size() == 1
                ? CsvIngester.stripExtensions(survivors.get(0).file().getName())
                : batch.batchId();

        List<PartitionOutput> outputs = PartitionWriter.write(conn, "transformed", dbDir,
                cfg.output().format(), cfg.output().compression(), baseName, partCols);
        List<LineageRow> lineage = LineageCollector.collect(conn, "transformed",
                batch.batchId(), srcIdToFile, outputs, partCols);

        return new IngestOutcome(batchStart, "SUCCESS", "", survivors, memberAudits,
                outputs, lineage, totalInputRows, batch.schemaName());
    }

    private static void dropView(Connection conn, String view) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP VIEW IF EXISTS \"" + view + "\"");
        } catch (Exception e) {
            log.debug("Could not drop view {} ({})", view, e.getMessage());
        }
    }

    /**
     * One-pass streaming ingest for a single-member native-{@code read_csv} batch.
     *
     * <p>Instead of {@code read_csv → raw_f0 (table) → raw_input (table) → transformed (table) →
     * COPY}, {@link #streamUnit} builds {@code raw_input} as a lazy {@code VIEW} over {@code read_csv}
     * and lets the one {@code CREATE TABLE transformed AS …} pull the data through in a single
     * streaming pass. Peak scratch drops from ~2× the file (raw_input + transformed coexisting) to a
     * single transformed table, and the redundant copy is gone — faster and far smaller.
     *
     * <p>Output and lineage are produced by the same {@link PartitionWriter}/{@link LineageCollector}
     * the multi-member path uses, over the same {@code transformed} table, so results are identical.
     * Quarantine/empty semantics mirror the per-member path exactly (unreadable → QUARANTINED_UNREADABLE;
     * 0 valid rows + rejects → QUARANTINED_MISMATCH; otherwise EMPTY/SUCCESS).
     */
    private IngestOutcome streamingIngest(Batch batch, Batch.Member m, PipelineConfig cfg,
                                          Connection conn, LocalDateTime batchStart) throws Exception {
        Map<String, Object> schema = m.selection().schema();
        LocalDateTime mStart = LocalDateTime.now();
        String dbDir = databaseDir(batch, cfg);
        List<String> partCols = partitionColumns(schema);
        String baseName = CsvIngester.stripExtensions(m.file().getName());

        Streamed s;
        try {
            s = streamUnit(conn, m.file(), m.file().getName(), schema, cfg,
                    dbDir, baseName, partCols, m.srcId(), batch.batchId());
        } catch (Exception e) {
            // read_csv failure (unreadable/undecodable) surfaces when the CTAS drives it.
            QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
            return empty(batch, batchStart, MemberAudit.rejected(m, "QUARANTINED_UNREADABLE", msg(e), mStart));
        }
        return finishSingle(batch, m, cfg, batchStart, mStart, s.parsed(), s.rejects(),
                s.outputs(), s.lineage());
    }

    /**
     * Streaming ingest for a single file that exceeds {@code processing.chunking.max_file_bytes}.
     * Splits the file into bounded, self-contained chunks ({@link FileChunker}) and streams each
     * through {@link #streamUnit} <em>one at a time</em> — dropping the transformed table and
     * deleting the chunk between iterations — so peak scratch stays ~one chunk regardless of total
     * file size. Each chunk writes its own per-partition output file ({@code <base>_cNNNN_out.*}),
     * which coexist in the partition dirs (valid Hive layout). Counts/outputs/lineage aggregate; the
     * <em>original</em> file remains the member for audit/markers/backup, so commit is unchanged.
     */
    private IngestOutcome chunkedIngest(Batch batch, Batch.Member m, PipelineConfig cfg,
                                        Connection conn, LocalDateTime batchStart) throws Exception {
        Map<String, Object> schema = m.selection().schema();
        LocalDateTime mStart = LocalDateTime.now();
        String dbDir = databaseDir(batch, cfg);
        List<String> partCols = partitionColumns(schema);
        String baseName = CsvIngester.stripExtensions(m.file().getName());

        String scratch = scratchDir(cfg);
        Path chunkDir = Paths.get(scratch != null ? scratch : System.getProperty("java.io.tmpdir"));

        long parsedTotal = 0, rejectTotal = 0;
        List<PartitionOutput> outputs = new ArrayList<>();
        List<LineageRow>      lineage = new ArrayList<>();
        int chunkCount = 0;

        try (FileChunker chunker = new FileChunker(m.file(), cfg, chunkDir)) {
            int seq = 0;
            while (chunker.hasNext()) {
                File chunk = chunker.next();
                chunkCount++;
                try {
                    // Lineage is attributed to the ORIGINAL file name, not the transient chunk.
                    Streamed s = streamUnit(conn, chunk, m.file().getName(), schema, cfg, dbDir,
                            baseName + "_c" + String.format("%05d", seq), partCols,
                            m.srcId(), batch.batchId());
                    parsedTotal += s.parsed();
                    rejectTotal += s.rejects();
                    outputs.addAll(s.outputs());
                    lineage.addAll(s.lineage());
                } finally {
                    Files.deleteIfExists(chunk.toPath());
                }
                seq++;
            }
        }
        log.info("[INGEST] [{}] streamed {} chunk(s): {} rows{}", m.file().getName(), chunkCount,
                String.format("%,d", parsedTotal), rejectTotal > 0 ? "  rejected=" + rejectTotal : "");

        return finishSingle(batch, m, cfg, batchStart, mStart, parsedTotal, rejectTotal, outputs, lineage);
    }

    /**
     * Stream one physical CSV unit (a whole file or one chunk) through the single-pass pipeline:
     * lazy {@code read_csv} view → one {@code transformed} table → partitioned write → lineage.
     * Drops the view and table before returning so the next chunk reuses the names and frees scratch.
     * Returns {@code parsed == 0} (with empty outputs) for an empty unit; throws if read_csv fails.
     *
     * @param physical     the file actually read (the original file, or a chunk file)
     * @param lineageName  the file name to record in lineage (the original file, even for chunks)
     */
    private static Streamed streamUnit(Connection conn, File physical, String lineageName,
                                       Map<String, Object> schema, PipelineConfig cfg, String dbDir,
                                       String baseName, List<String> partCols, int srcId, String batchId)
            throws Exception {
        dropTable(conn, "transformed");
        DuckDbCsvIngester.createRawInputView(physical, conn, schema, cfg, "raw_input", srcId);
        DataTransformer.materialize(conn, schema, cfg);   // read_csv runs here (streaming)

        long parsed  = countRows(conn, "transformed");
        long rejects = DuckDbCsvIngester.drainRejects(conn, physical, cfg);
        if (parsed == 0) {
            dropTable(conn, "transformed");
            return new Streamed(0, rejects, List.of(), List.of());
        }
        List<PartitionOutput> outputs = PartitionWriter.write(conn, "transformed", dbDir,
                cfg.output().format(), cfg.output().compression(), baseName, partCols);
        List<LineageRow> lineage = LineageCollector.collect(conn, "transformed",
                batchId, Map.of(srcId, lineageName), outputs, partCols);
        dropTable(conn, "transformed");
        return new Streamed(parsed, rejects, outputs, lineage);
    }

    /** Apply the shared empty/quarantine/success decision for a single-member outcome. */
    private IngestOutcome finishSingle(Batch batch, Batch.Member m, PipelineConfig cfg,
                                       LocalDateTime batchStart, LocalDateTime mStart,
                                       long parsed, long rejects,
                                       List<PartitionOutput> outputs, List<LineageRow> lineage)
            throws Exception {
        if (parsed == 0 && rejects > 0) {
            QuarantineManager.quarantine(m.file(), "field_mismatch", true, cfg);
            String reason = String.format("0 valid rows; %d row(s) rejected (field mismatch)", rejects);
            return empty(batch, batchStart, MemberAudit.rejected(m, "QUARANTINED_MISMATCH", reason, mStart));
        }
        if (parsed == 0)
            return empty(batch, batchStart, MemberAudit.accepted(m, 0, 0, mStart));

        return new IngestOutcome(batchStart, "SUCCESS", "", List.of(m),
                List.of(MemberAudit.accepted(m, parsed, rejects, mStart)),
                outputs, lineage, parsed, batch.schemaName());
    }

    private IngestOutcome empty(Batch batch, LocalDateTime batchStart, MemberAudit memberAudit) {
        return new IngestOutcome(batchStart, "EMPTY", "", List.of(), List.of(memberAudit),
                List.of(), List.of(), 0, batch.schemaName());
    }

    private static String databaseDir(Batch batch, PipelineConfig cfg) {
        return (batch.table() != null && !batch.table().isBlank())
                ? Paths.get(cfg.dirs().database(), batch.table()).toString()
                : cfg.dirs().database();
    }

    private static List<String> partitionColumns(Map<String, Object> schema) {
        List<PartitionDef> partDefs = PartitionDef.fromSchema(schema);
        return partDefs.isEmpty() ? List.of("year", "month", "day")
                                  : PartitionDef.columnNames(partDefs);
    }

    /** Per-unit streaming result aggregated by {@link #chunkedIngest}. */
    private record Streamed(long parsed, long rejects,
                            List<PartitionOutput> outputs, List<LineageRow> lineage) {}

    private static long countRows(Connection conn, String table) throws java.sql.SQLException {
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
