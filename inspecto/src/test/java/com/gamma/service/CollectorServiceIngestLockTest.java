package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.inspector.MultiCollectorProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for the single shared {@code ingestLock} that serializes every ingest entry
 * path — the scheduled poll cycle ({@link CollectorService#runAllOnce()}) and the operator trigger
 * ({@link CollectorService#runPipeline(String)}) — so a manual run can never overlap a cycle (or vice
 * versa). This is the load-bearing invariant of the pending {@code PipelineScheduler} extraction
 * (modularization-optimization-plan §2.2.1a): the scheduler must receive a <em>shared reference</em> to
 * this one lock, never a clone. Pinning it here <em>before</em> the extraction turns a would-be silent
 * live-deadlock (a cloned lock compiles fine) into a red test.
 *
 * <p>Both tests reflectively hold the lock from the test thread and assert the ingest path blocks until
 * it is released — a deterministic stand-in for two ingest paths racing, without needing a blocking hook
 * inside a run.
 */
class CollectorServiceIngestLockTest {

    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n";

    private static Path source(Path root) throws Exception {
        Path toon = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), CSV);
        return toon;
    }

    private static long outputCsvCount(Path root) throws Exception {
        Path db = root.resolve("db");
        if (!Files.exists(db)) return 0;
        try (var s = Files.walk(db)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".csv")).count();
        }
    }

    /** The one lock the poll cycle and every operator trigger contend on. Reflected so the test can hold it. */
    private static ReentrantLock ingestLock(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("ingestLock");
        f.setAccessible(true);
        return (ReentrantLock) f.get(svc);
    }

    @Test
    void pollCycleBlocksWhileIngestLockIsHeld(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"));
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            ReentrantLock lock = ingestLock(svc);
            lock.lock();
            Future<MultiCollectorProcessor.RunResult> cycle;
            try {
                cycle = ex.submit(svc::runAllOnce);
                assertThrows(TimeoutException.class, () -> cycle.get(500, MILLISECONDS),
                        "runAllOnce must block on the shared ingestLock while it is held elsewhere");
                assertEquals(0, outputCsvCount(dir.resolve("a")),
                        "...and produce nothing while blocked");
            } finally {
                lock.unlock();
            }
            assertEquals(1, cycle.get(5, SECONDS).total(),
                    "...then run to completion once the lock is released");
            assertTrue(outputCsvCount(dir.resolve("a")) >= 1, "the unblocked cycle produced output");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void manualTriggerBlocksWhileIngestLockIsHeld(@TempDir Path dir) throws Exception {
        Path a = source(dir.resolve("a"));
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            String name = svc.pipelines().get(0).name();
            ReentrantLock lock = ingestLock(svc);
            lock.lock();
            Future<?> trigger;
            try {
                trigger = ex.submit(() -> svc.runPipeline(name));
                assertThrows(TimeoutException.class, () -> trigger.get(500, MILLISECONDS),
                        "runPipeline must contend on the SAME ingestLock — a manual run cannot overlap a cycle");
            } finally {
                lock.unlock();
            }
            trigger.get(5, SECONDS);
            assertTrue(outputCsvCount(dir.resolve("a")) >= 1, "the unblocked trigger produced output");
        } finally {
            ex.shutdownNow();
        }
    }
}
