package com.gamma.inspector;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MultiSourceProcessor} — the multi-source orchestrator.
 * Verifies parallel execution of several sources, failure isolation, and the
 * config-path resolution (file + directory expansion).
 */
class MultiSourceProcessorTest {

    private static final String SCHEMA = PipelineConfigBatchTest.miniSchema();

    /** Build a self-contained source under {@code root}, seed its inbox, return the toon path. */
    private static Path source(Path root, String inboxCsv) throws Exception {
        Path toon = TestConfigs.csv(root, SCHEMA).write();
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), inboxCsv);
        return toon;
    }

    private static long outputFileCount(Path root) throws Exception {
        Path db = root.resolve("db");
        if (!Files.exists(db)) return 0;
        try (Stream<Path> s = Files.walk(db)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".csv")).count();
        }
    }

    /** Two independent sources run and both produce output. */
    @Test
    void runsMultipleSourcesAndProducesOutput(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-02\n");
        Path b = source(dir.resolve("b"), "ID,AMT,EVENT_DATE\n3,30,2020-02-01\n");

        MultiSourceProcessor.RunResult r = MultiSourceProcessor.runAll(List.of(a, b), 2);

        assertEquals(2, r.total());
        assertEquals(0, r.failed(), "both sources should succeed");
        assertTrue(outputFileCount(dir.resolve("a")) >= 1, "source a produced output");
        assertTrue(outputFileCount(dir.resolve("b")) >= 1, "source b produced output");
    }

    /** A failing source (unloadable config) is isolated — the good source still runs. */
    @Test
    void failureInOneSourceDoesNotAbortOthers(@TempDir Path dir) throws Exception {
        Path good = source(dir.resolve("good"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n");

        // A pipeline toon pointing at a schema_file that does not exist → load throws.
        Path badRoot = dir.resolve("bad");
        Files.createDirectories(badRoot);
        Path bad = badRoot.resolve("bad_pipeline.toon");
        Files.writeString(bad, """
                name: BAD_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*"
                  schema_file: "%s/does_not_exist.toon"
                """.formatted(badRoot, badRoot, badRoot, badRoot, badRoot)
                .replace("\\", "/"));

        MultiSourceProcessor.RunResult r = MultiSourceProcessor.runAll(List.of(good, bad), 2);

        assertEquals(2, r.total());
        assertEquals(1, r.failed(), "the bad source should be counted as failed");
        assertEquals(1, r.succeeded());
        assertTrue(outputFileCount(dir.resolve("good")) >= 1,
                "good source must still produce output despite the bad one failing");
    }

    /** resolveConfigs expands a directory to its *_pipeline.toon files (sorted) and accepts file args. */
    @Test
    void resolveConfigsExpandsDirectoriesAndFiles(@TempDir Path dir) throws Exception {
        Path d = dir.resolve("configs");
        Files.createDirectories(d);
        Files.writeString(d.resolve("alpha_pipeline.toon"), "x");
        Files.writeString(d.resolve("beta_pipeline.toon"),  "x");
        Files.writeString(d.resolve("notes.txt"), "ignore me");          // not a pipeline toon
        Path loose = dir.resolve("gamma_pipeline.toon");
        Files.writeString(loose, "x");

        List<Path> resolved = MultiSourceProcessor.resolveConfigs(
                new String[]{ d.toString(), loose.toString() });

        assertEquals(3, resolved.size(), "two from dir + one file arg");
        assertTrue(resolved.stream().anyMatch(p -> p.getFileName().toString().equals("alpha_pipeline.toon")));
        assertTrue(resolved.stream().anyMatch(p -> p.getFileName().toString().equals("beta_pipeline.toon")));
        assertTrue(resolved.stream().anyMatch(p -> p.getFileName().toString().equals("gamma_pipeline.toon")));
        // The .txt must be excluded.
        assertFalse(resolved.stream().anyMatch(p -> p.getFileName().toString().endsWith(".txt")));
    }

    /** Missing paths are skipped, not fatal. */
    @Test
    void resolveConfigsSkipsMissingPaths(@TempDir Path dir) throws Exception {
        List<Path> resolved = MultiSourceProcessor.resolveConfigs(
                new String[]{ dir.resolve("nope").toString() });
        assertTrue(resolved.isEmpty());
    }
}
