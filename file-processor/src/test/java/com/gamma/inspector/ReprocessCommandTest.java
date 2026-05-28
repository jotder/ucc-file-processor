package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ReprocessCommandTest {

    @Test
    void deletesOutputsRestoresFilesAndReprocesses(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.pollDir);
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");
        Files.writeString(inbox.resolve("b.csv"), "ID,AMT,EVENT_DATE\nb,2.0,2020-04-03\n");

        SourceProcessor.run(cfg);

        // Find the batch id from the single manifest written.
        String batchId;
        try (Stream<Path> w = Files.walk(Path.of(cfg.manifestsDir))) {
            Path mf = w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
            batchId = mf.getFileName().toString().replace(".json", "");
        }

        // Sanity: outputs + markers + backup present before reprocess.
        assertTrue(Files.exists(Path.of(cfg.backupDir, "a.csv")));
        assertTrue(Files.exists(Path.of(cfg.markersDir, "a.csv.processed")));

        // Reprocess: must restore files, delete old outputs/markers, supersede manifest, re-run.
        ReprocessCommand.run(toon.toString(), batchId);

        // Old manifest superseded.
        assertTrue(Files.exists(Path.of(cfg.manifestsDir, batchId + ".json.superseded")));
        // Markers exist again (re-run re-created them) and outputs exist.
        assertTrue(Files.exists(Path.of(cfg.markersDir, "a.csv.processed")));
        try (Stream<Path> w = Files.walk(Path.of(cfg.databaseDir))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().endsWith("_out.csv")));
        }
    }
}
