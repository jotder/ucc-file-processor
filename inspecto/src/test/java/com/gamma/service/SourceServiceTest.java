package com.gamma.service;

import com.gamma.etl.BatchEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.inspector.MultiSourceProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for {@link SourceService} (M1) — registry runs, batch-commit
 * events, recovery via the commit log, and the scheduled poll cycle.
 */
class SourceServiceTest {

    private static Path source(Path root, String inboxCsv) throws Exception {
        Path toon = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), inboxCsv);
        return toon;
    }

    private static long outputCsvCount(Path root) throws Exception {
        Path db = root.resolve("db");
        if (!Files.exists(db)) return 0;
        try (Stream<Path> s = Files.walk(db)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".csv")).count();
        }
    }

    @Test
    void runAllOnceProducesOutputAndEmitsCommitEvents(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-02\n");
        Path b = source(dir.resolve("b"), "ID,AMT,EVENT_DATE\n3,30,2020-02-01\n");

        List<BatchEvent> events = Collections.synchronizedList(new ArrayList<>());
        try (SourceService svc = new SourceService(List.of(a, b), 3600, 2)) {
            svc.eventBus().subscribe(events::add);

            MultiSourceProcessor.RunResult r = svc.runAllOnce();

            assertEquals(2, r.total());
            assertEquals(0, r.failed());
            assertTrue(outputCsvCount(dir.resolve("a")) >= 1, "source a produced output");
            assertTrue(outputCsvCount(dir.resolve("b")) >= 1, "source b produced output");

            assertTrue(events.size() >= 2, "a commit event per pipeline, got " + events.size());
            assertTrue(events.stream().allMatch(e -> "SUCCESS".equals(e.status())));
            assertTrue(events.stream().allMatch(e -> !e.partitions().isEmpty()),
                    "commit events carry the written partitions (for downstream enrichment)");
        }
    }

    @Test
    void committedBatchesVisibleViaStatusStoreAfterRun(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n");
        try (SourceService svc = new SourceService(List.of(a), 3600, 1)) {
            svc.runAllOnce();
        }
        // Reload (fresh run timestamp) — the commit log is persistent, so committed
        // batches from the run are visible.
        PipelineConfig cfg = PipelineConfig.load(a.toString());
        assertFalse(new FileStatusStore().committedBatches(cfg).isEmpty(),
                "the committed batch should be recorded in the commit log");
    }

    @Test
    void inactivePipelineIsSkippedByTheCycle(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n");
        // Flip the activation gate off on disk (TestConfigs emits `active: true`).
        Files.writeString(a, Files.readString(a).replace("active: true", "active: false"));
        try (SourceService svc = new SourceService(List.of(a), 3600, 1)) {
            MultiSourceProcessor.RunResult r = svc.runAllOnce();
            assertEquals(0, r.total(), "an inactive (active:false) pipeline is not run");
            assertEquals(0, outputCsvCount(dir.resolve("a")), "an inactive pipeline produces no output");
        }
    }

    @Test
    void pipelineWithNoActiveKeyDefaultsInactive(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n");
        // Remove the activation key entirely — the default is OFF (opt-in).
        Files.writeString(a, Files.readString(a).replace("active: true\n", ""));
        try (SourceService svc = new SourceService(List.of(a), 3600, 1)) {
            assertEquals(0, svc.runAllOnce().total(), "absent `active` key defaults to not-run");
        }
    }

    @Test
    void startSchedulesAPollCycle(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n");
        try (SourceService svc = new SourceService(List.of(a), 1, 1)) {
            svc.start();   // schedules an immediate (initialDelay 0) cycle
            // Wait up to 5s for the scheduled cycle to produce output.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (outputCsvCount(dir.resolve("a")) == 0 && System.nanoTime() < deadline)
                Thread.sleep(100);
            assertTrue(outputCsvCount(dir.resolve("a")) >= 1, "scheduled poll cycle should produce output");
        }
    }
}
