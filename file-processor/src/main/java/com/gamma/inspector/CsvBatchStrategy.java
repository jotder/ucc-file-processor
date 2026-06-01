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

import static com.gamma.inspector.BatchIngestStrategy.dropTable;
import static com.gamma.inspector.BatchIngestStrategy.msg;

/**
 * Built-in CSV ingest path. Ingests each member into a per-file temp table, inserts
 * accepted rows into a shared {@code raw_input} tagged with {@code __src_id}, transforms
 * once, writes consolidated partition output, and computes the lineage matrix. Rejected
 * members are quarantined; their rows never reach {@code raw_input}.
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

        return new IngestOutcome(batchStart, batchStatus, batchError, survivors, memberAudits,
                outputs, lineage, totalInputRows, batch.schemaName());
    }
}
