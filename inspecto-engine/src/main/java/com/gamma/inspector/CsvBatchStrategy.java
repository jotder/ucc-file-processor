package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.inspector.BatchIngestStrategy.configure;
import static com.gamma.inspector.BatchIngestStrategy.consolidatedBaseName;
import static com.gamma.inspector.BatchIngestStrategy.databaseDir;
import static com.gamma.inspector.BatchIngestStrategy.dropTable;
import static com.gamma.inspector.BatchIngestStrategy.msg;
import static com.gamma.inspector.BatchIngestStrategy.openTempDb;
import static com.gamma.inspector.BatchIngestStrategy.partitionColumns;
import static com.gamma.inspector.BatchIngestStrategy.writeAndTrace;

/**
 * Built-in CSV ingest path. Tags every accepted row with {@code __src_id}, transforms once, writes
 * consolidated partition output, and computes the lineage matrix; rejected members are quarantined
 * and their rows never reach the transform.
 *
 * <p>How rows reach the {@code transformed} table depends on the parse engine:
 * <ul>
 *   <li><b>Native {@code read_csv} engine</b> — fully streaming, no intermediate data copies; a
 *       single-member batch streams {@code read_csv → transform → COPY} in one pass (chunked for huge
 *       files), a multi-member batch {@code UNION ALL}s a lazy {@code read_csv} view per member into
 *       one transform, materialising the data exactly once. Handled by {@link NativeCsvStreamingEngine}.</li>
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
                                ? NativeCsvStreamingEngine.chunkedIngest(batch, only, cfg, conn, batchStart)
                                : NativeCsvStreamingEngine.streamingIngest(batch, only, cfg, conn, batchStart);
                    }
                    return NativeCsvStreamingEngine.unionStreamingIngest(batch, cfg, conn, batchStart);
                }

                boolean rawCreated = false;
                int memberIdx = 0;
                for (Batch.Member m : batch.members()) {
                    IngestProgress.track(cfg.identity().pipelineName(), batch.batchId(),
                            m.file().getName(), ++memberIdx, batch.members().size());
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
                        // Readable but zero ingestable rows (empty / header-only): quarantine under
                        // `empty` so the file leaves the inbox. An EMPTY batch never backs up or marks,
                        // so leaving it would have the poll loop rediscover and reprocess it forever.
                        QuarantineManager.quarantine(m.file(), QuarantineManager.REASON_EMPTY, false, cfg);
                        memberAudits.add(MemberAudit.rejected(m, "QUARANTINED_EMPTY",
                                "0 valid rows (empty/header-only file)", mStart));
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

                    var written = writeAndTrace(conn, "transformed", partitionColumns(schema),
                            cfg, databaseDir(batch, cfg), consolidatedBaseName(survivors, batch),
                            batch.batchId(), srcIdToFile);
                    outputs = written.outputs();
                    lineage = written.lineage();
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

}
