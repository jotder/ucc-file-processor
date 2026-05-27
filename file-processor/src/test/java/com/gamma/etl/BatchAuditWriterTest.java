package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchAuditWriterTest {

    @Test
    void writesHeadersAndRows(@TempDir Path dir) throws Exception {
        String statusCsv  = dir.resolve("p_status_TS.csv").toString();
        String batchesCsv = dir.resolve("p_batches_TS.csv").toString();
        String lineageCsv = dir.resolve("p_lineage_TS.csv").toString();
        BatchAuditWriter w = new BatchAuditWriter(statusCsv, batchesCsv, lineageCsv);

        var fileRows = List.of(
                new BatchAuditWriter.FileRow("2026-05-27 10:30:00", "2026-05-27 10:30:01",
                        "a.csv", "SUCCESS", 2, 0, List.of("/db/B1_out.csv"), List.of(120L), 1000, "", "B1"),
                new BatchAuditWriter.FileRow("2026-05-27 10:30:00", "2026-05-27 10:30:01",
                        "bad.csv", "QUARANTINED_MISMATCH", 0, 3, List.of(), List.of(), 50, "0 valid rows", "B1"));
        var batchRow = new BatchAuditWriter.BatchRow("B1", "mini_etl", "mini", "",
                "2026-05-27 10:30:00", "2026-05-27 10:30:02", "SUCCESS",
                2, 1, 2, 2, 1, 120L, 2000, "");
        var lineage = List.of(new LineageRow("B1", 0, "a.csv", "/db/B1_out.csv", "year=2020/month=04/day=03", 2));

        w.flush(batchRow, fileRows, lineage);

        String status = Files.readString(Path.of(statusCsv));
        assertTrue(status.startsWith("start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error,batch_id"));
        assertTrue(status.contains("a.csv"));
        assertTrue(status.contains("QUARANTINED_MISMATCH"));

        String batches = Files.readString(Path.of(batchesCsv));
        assertTrue(batches.contains("batch_id,pipeline,schema_name,output_table"));
        assertTrue(batches.contains("B1"));

        String lin = Files.readString(Path.of(lineageCsv));
        assertTrue(lin.startsWith("batch_id,src_id,input_file,output_file,partition,row_count"));
        assertTrue(lin.contains("year=2020/month=04/day=03"));
    }
}
