package com.gamma.service;

import com.gamma.etl.BatchAuditWriter;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileStatusStoreTest {

    /**
     * Regression: Windows output paths contain backslashes. The audit reader must preserve them.
     * OpenCSV's default parser treats '\' as an escape character and silently strips it
     * ("C:\\db\\out.csv" -> "C:dbout.csv"); the RFC4180 parser reads it literally. This writes a
     * real audit row via BatchAuditWriter and reads it back through FileStatusStore end-to-end.
     */
    @Test
    void preservesBackslashesInWindowsOutputPaths(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(
                PipelineConfigBatchTest.writePipeline(dir, "").toString());

        Path statusDir = Path.of(cfg.dirs().statusFilePath()).toAbsolutePath().getParent();
        Files.createDirectories(statusDir);
        String name = cfg.identity().pipelineName();   // reader globs <name>_status_*.csv
        String winPath = "C:\\data\\db\\year=2020\\month=04\\day=03\\B1_out.csv";

        String statusCsv  = statusDir.resolve(name + "_status_TEST.csv").toString();
        String batchesCsv = statusDir.resolve(name + "_batches_TEST.csv").toString();
        String lineageCsv = statusDir.resolve(name + "_lineage_TEST.csv").toString();
        BatchAuditWriter w = new BatchAuditWriter(statusCsv, batchesCsv, lineageCsv);

        var fileRow = new BatchAuditWriter.FileRow("2026-06-09 08:00:00", "2026-06-09 08:00:01",
                "a.csv", "SUCCESS", 2, 0, List.of(winPath), List.of(120L), 1000, "", "B1");
        var batchRow = new BatchAuditWriter.BatchRow("B1", name, "mini", "",
                "2026-06-09 08:00:00", "2026-06-09 08:00:02", "SUCCESS",
                1, 0, 2, 2, 1, 120L, 2000, "");
        var lineage = List.of(new LineageRow("B1", 0, "a.csv", winPath, "year=2020/month=04/day=03", 2));

        w.flush(batchRow, List.of(fileRow), lineage);

        FileStatusStore store = new FileStatusStore();

        List<Map<String, String>> files = store.files(cfg);
        assertEquals(1, files.size());
        assertEquals(winPath, files.get(0).get("output_paths"),
                "backslashes in the Windows output path must survive the CSV round-trip");

        List<Map<String, String>> lin = store.lineage(cfg, "B1");
        assertEquals(1, lin.size());
        assertEquals(winPath, lin.get(0).get("output_file"),
                "lineage output_file must keep its backslashes too");
    }
}
