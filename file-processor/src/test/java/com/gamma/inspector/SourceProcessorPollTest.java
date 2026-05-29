package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SourceProcessorPollTest {

    @Test
    void consolidatesManySmallFilesIntoOneBatch(@TempDir Path dir) throws Exception {
        String batch = """
              batch:
                max_files: 100
                max_bytes: 268435456
            """;
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, batch);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (int i = 0; i < 6; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");

        SourceProcessor.run(cfg);

        // All 6 tiny files consolidate into ONE partition's single output file.
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertEquals(1, w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).count());
        }
        // One batch row recorded.
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertEquals(2, batches.split("\n").length, "header + 1 batch row");
        // Re-running is a no-op: markers skip all files (still exactly one output file).
        SourceProcessor.run(cfg);
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertEquals(1, w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).count());
        }
    }
}
