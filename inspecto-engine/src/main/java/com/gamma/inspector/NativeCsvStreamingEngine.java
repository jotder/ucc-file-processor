package com.gamma.inspector;

import com.gamma.etl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

import static com.gamma.inspector.BatchIngestStrategy.consolidatedBaseName;
import static com.gamma.inspector.BatchIngestStrategy.databaseDir;
import static com.gamma.inspector.BatchIngestStrategy.dropTable;
import static com.gamma.inspector.BatchIngestStrategy.msg;
import static com.gamma.inspector.BatchIngestStrategy.partitionColumns;
import static com.gamma.inspector.BatchIngestStrategy.scratchDir;
import static com.gamma.inspector.BatchIngestStrategy.unionAll;
import static com.gamma.inspector.BatchIngestStrategy.writeAndTrace;

/**
 * The native {@code read_csv} streaming ingest engine for {@link CsvBatchStrategy}: the fully
 * streaming paths that materialise a batch's data exactly once, with no per-member
 * {@code raw_f}/{@code raw_input} table copies. {@code CsvBatchStrategy.ingest} dispatches here when
 * {@link DuckDbCsvIngester#decideNative} selects the native engine:
 * <ul>
 *   <li>single member → one streaming pass ({@link #streamingIngest}), chunked for huge files
 *       ({@link #chunkedIngest}) so peak scratch stays bounded;</li>
 *   <li>many members → each becomes a lazy {@code read_csv} view {@code UNION ALL}-ed into one
 *       transform ({@link #unionStreamingIngest}).</li>
 * </ul>
 * The Java parse-engine path (per-member materialise → {@code raw_input}) stays in
 * {@link CsvBatchStrategy}. All methods are static; the connection is owned by the caller.
 *
 * <p>The logger keeps {@code CsvBatchStrategy}'s category so log output is unchanged from when this
 * code lived there.
 */
final class NativeCsvStreamingEngine {

    private static final Logger log = LoggerFactory.getLogger(CsvBatchStrategy.class);

    private NativeCsvStreamingEngine() {}

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
    static IngestOutcome unionStreamingIngest(Batch batch, PipelineConfig cfg,
                                              Connection conn, LocalDateTime batchStart) throws Exception {
        Map<String, Object>  schema       = batch.members().get(0).selection().schema();
        Map<Integer, String> srcIdToFile  = new LinkedHashMap<>();
        List<Batch.Member>   survivors    = new ArrayList<>();
        List<MemberAudit>    memberAudits = new ArrayList<>();
        List<String>         memberViews  = new ArrayList<>();
        long totalInputRows = 0;

        int memberIdx = 0;
        for (Batch.Member m : batch.members()) {
            IngestProgress.track(cfg.identity().pipelineName(), batch.batchId(),
                    m.file().getName(), ++memberIdx, batch.members().size());
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
            if (parsed == 0) {   // readable but zero rows (empty/header-only): quarantine under `empty`
                                 // so it leaves the inbox (an EMPTY batch never backs up/marks → would loop).
                QuarantineManager.quarantine(m.file(), QuarantineManager.REASON_EMPTY, false, cfg);
                memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_EMPTY",
                        "0 valid rows (empty/header-only file)", mStart));
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
        try (Statement st = conn.createStatement()) {
            st.execute("DROP VIEW IF EXISTS \"raw_input\"");
            st.execute("CREATE VIEW \"raw_input\" AS " + unionAll(memberViews));
        }
        DataTransformer.materialize(conn, schema, cfg);   // single streaming pass over all members

        var written = writeAndTrace(conn, "transformed", partitionColumns(schema),
                cfg, databaseDir(batch, cfg), consolidatedBaseName(survivors, batch),
                batch.batchId(), srcIdToFile);

        return new IngestOutcome(batchStart, "SUCCESS", "", survivors, memberAudits,
                written.outputs(), written.lineage(), totalInputRows, batch.schemaName());
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
    static IngestOutcome streamingIngest(Batch batch, Batch.Member m, PipelineConfig cfg,
                                         Connection conn, LocalDateTime batchStart) throws Exception {
        Map<String, Object> schema = m.selection().schema();
        LocalDateTime mStart = LocalDateTime.now();
        String dbDir = databaseDir(batch, cfg);
        List<String> partCols = partitionColumns(schema);
        String baseName = CsvIngester.stripExtensions(m.file().getName());
        IngestProgress.track(cfg.identity().pipelineName(), batch.batchId(),
                m.file().getName(), 1, 1);

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
    static IngestOutcome chunkedIngest(Batch batch, Batch.Member m, PipelineConfig cfg,
                                       Connection conn, LocalDateTime batchStart) throws Exception {
        Map<String, Object> schema = m.selection().schema();
        LocalDateTime mStart = LocalDateTime.now();
        String dbDir = databaseDir(batch, cfg);
        List<String> partCols = partitionColumns(schema);
        String baseName = CsvIngester.stripExtensions(m.file().getName());
        IngestProgress.track(cfg.identity().pipelineName(), batch.batchId(),
                m.file().getName(), 1, 1);

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
        var written = writeAndTrace(conn, "transformed", partCols, cfg, dbDir, baseName,
                batchId, Map.of(srcId, lineageName));
        dropTable(conn, "transformed");
        return new Streamed(parsed, rejects, written.outputs(), written.lineage());
    }

    /** Apply the shared empty/quarantine/success decision for a single-member outcome. */
    private static IngestOutcome finishSingle(Batch batch, Batch.Member m, PipelineConfig cfg,
                                              LocalDateTime batchStart, LocalDateTime mStart,
                                              long parsed, long rejects,
                                              List<PartitionOutput> outputs, List<LineageRow> lineage)
            throws Exception {
        if (parsed == 0 && rejects > 0) {
            QuarantineManager.quarantine(m.file(), "field_mismatch", true, cfg);
            String reason = String.format("0 valid rows; %d row(s) rejected (field mismatch)", rejects);
            return empty(batch, batchStart, MemberAudit.rejected(m, "QUARANTINED_MISMATCH", reason, mStart));
        }
        if (parsed == 0) {
            // Readable but zero rows (empty/header-only): quarantine under `empty` so it leaves the
            // inbox — an EMPTY batch never backs up/marks, so otherwise the poll loop reprocesses it forever.
            QuarantineManager.quarantine(m.file(), QuarantineManager.REASON_EMPTY, false, cfg);
            return empty(batch, batchStart, MemberAudit.rejected(m, "QUARANTINED_EMPTY",
                    "0 valid rows (empty/header-only file)", mStart));
        }

        return new IngestOutcome(batchStart, "SUCCESS", "", List.of(m),
                List.of(MemberAudit.accepted(m, parsed, rejects, mStart)),
                outputs, lineage, parsed, batch.schemaName());
    }

    private static IngestOutcome empty(Batch batch, LocalDateTime batchStart, MemberAudit memberAudit) {
        return new IngestOutcome(batchStart, "EMPTY", "", List.of(), List.of(memberAudit),
                List.of(), List.of(), 0, batch.schemaName());
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
