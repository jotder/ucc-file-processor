package com.gamma.agent.diagnose;

import com.gamma.assist.Diagnosis;
import com.gamma.etl.BatchEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 (v3.7.0, M7): the deterministic, model-free core of the failure reactor — the
 * {@link HeuristicDiagnoser} severity/cause matrix, the {@link DiagnosisStore} ring, and the
 * {@link FailureReactor} hand-off (filter, bound, never block the publisher). All CPU-only, no Ollama.
 */
class FailureReactorTest {

    private static final long EPOCH = 1_700_000_000_000L;

    private static BatchEvent failed(String batchId, String error, String offending, long outRows, long errRows) {
        return new BatchEvent("EVENTS", batchId, "FAILED", List.of(), outRows, 10L, 1, error, offending, errRows);
    }

    private static FailureReactor.Diagnoser heuristic() {
        return e -> HeuristicDiagnoser.diagnose(e, EPOCH, List.of());
    }

    // ── HeuristicDiagnoser ───────────────────────────────────────────────

    @Test
    void severityReflectsHowMuchSurvived() {
        assertEquals(Diagnosis.Severity.CRITICAL,
                HeuristicDiagnoser.severityOf(failed("b", "boom", null, 0, 5)), "FAILED with no output");
        assertEquals(Diagnosis.Severity.WARNING,
                HeuristicDiagnoser.severityOf(failed("b", "boom", null, 3, 1)), "FAILED but some output");
        BatchEvent partial = new BatchEvent("EVENTS", "b", "SUCCESS", List.of(), 9, 1L, 0, null, null, 2);
        assertEquals(Diagnosis.Severity.WARNING, HeuristicDiagnoser.severityOf(partial), "success w/ error rows");
        BatchEvent clean = new BatchEvent("EVENTS", "b", "SUCCESS", List.of(), 9, 1L, 0);
        assertEquals(Diagnosis.Severity.INFO, HeuristicDiagnoser.severityOf(clean), "clean success");
    }

    @Test
    void rootCausePatternMatchesAndNamesOffendingFile() {
        assertTrue(HeuristicDiagnoser.rootCauseOf(failed("b", "schema selector mismatch", "bad.csv", 0, 3))
                .toLowerCase().contains("schema/selector mismatch"));
        assertTrue(HeuristicDiagnoser.rootCauseOf(failed("b", "row 4 malformed", "x.csv", 0, 1))
                .toLowerCase().contains("parse error"));
        assertTrue(HeuristicDiagnoser.rootCauseOf(failed("b", "java heap space (OOM)", null, 0, 0))
                .toLowerCase().contains("memory"));
        String named = HeuristicDiagnoser.rootCauseOf(failed("b", "schema mismatch", "bad.csv", 0, 3));
        assertTrue(named.contains("bad.csv"), "names the offending file");
        assertTrue(named.contains("3 row"), "mentions error-row count");
        assertTrue(HeuristicDiagnoser.rootCauseOf(failed("b", "", null, 0, 0))
                .toLowerCase().contains("no error detail"), "blank error -> generic guidance");
    }

    // ── DiagnosisStore ───────────────────────────────────────────────────

    @Test
    void storeIsBoundedNewestFirst() {
        DiagnosisStore store = new DiagnosisStore(2);
        store.add(HeuristicDiagnoser.diagnose(failed("b1", "x", null, 0, 0), EPOCH, List.of()));
        store.add(HeuristicDiagnoser.diagnose(failed("b2", "x", null, 0, 0), EPOCH, List.of()));
        store.add(HeuristicDiagnoser.diagnose(failed("b3", "x", null, 0, 0), EPOCH, List.of()));
        List<Diagnosis> recent = store.recent(10);
        assertEquals(2, recent.size(), "capacity enforced (oldest evicted)");
        assertEquals("b3", recent.get(0).batchId(), "newest first");
        assertEquals("b2", recent.get(1).batchId());
        assertEquals(0, store.recent(0).size(), "limit 0 -> empty");
    }

    // ── FailureReactor ───────────────────────────────────────────────────

    @Test
    void drainsFailedEventSynchronouslyWithDirectExecutor() {
        DiagnosisStore store = new DiagnosisStore();
        try (FailureReactor reactor = new FailureReactor(Runnable::run, 16, heuristic(), store, null)) {
            reactor.onEvent(failed("B1", "schema mismatch", "bad.csv", 0, 3));
            assertEquals(1, store.size(), "a FAILED event is diagnosed + stored");
            Diagnosis d = store.recent(1).get(0);
            assertEquals("B1", d.batchId());
            assertEquals(Diagnosis.Severity.CRITICAL, d.severity());
            assertTrue(d.heuristicOnly(), "no model contributed");
        }
    }

    @Test
    void ignoresNonFailedEvents() {
        DiagnosisStore store = new DiagnosisStore();
        try (FailureReactor reactor = new FailureReactor(Runnable::run, 16, heuristic(), store, null)) {
            reactor.onEvent(new BatchEvent("EVENTS", "ok1", "SUCCESS", List.of(), 5, 1L, 0));
            assertEquals(0, store.size(), "SUCCESS events are not diagnosed");
        }
    }

    @Test
    void boundedQueueShedsLoadWhenWorkBlocks() throws Exception {
        // A never-released executor: tasks are submitted but never run, so the queue fills and overflows.
        DiagnosisStore store = new DiagnosisStore();
        Executor parked = r -> { /* drop the drain task on the floor */ };
        try (FailureReactor reactor = new FailureReactor(parked, 2, heuristic(), store, null)) {
            for (int i = 0; i < 5; i++) reactor.onEvent(failed("b" + i, "x", null, 0, 0));
            assertEquals(3, reactor.droppedCount(), "queue cap 2 -> 3 of 5 shed");
            assertEquals(0, store.size(), "nothing drained (executor parked) — and memory stayed bounded");
        }
    }

    @Test
    void onEventDoesNotBlockThePublisher() throws Exception {
        // Default (async daemon VT) executor + a diagnoser that blocks until released. onEvent must
        // return well before the diagnosis completes — proof the ingest thread is never held.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger diagnosed = new AtomicInteger();
        FailureReactor.Diagnoser slow = e -> {
            try { assertTrue(release.await(5, TimeUnit.SECONDS), "released"); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            diagnosed.incrementAndGet();
            return HeuristicDiagnoser.diagnose(e, EPOCH, List.of());
        };
        DiagnosisStore store = new DiagnosisStore();
        // Signal completion via the audit sink — it runs AFTER store.add in the worker, so the
        // assertion below never races the store write.
        try (FailureReactor reactor = new FailureReactor(slow, store, d -> done.countDown())) {
            long t0 = System.nanoTime();
            reactor.onEvent(failed("B1", "x", null, 0, 0));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(0, diagnosed.get(), "diagnosis hasn't run yet — work was handed off");
            assertTrue(elapsedMs < 1_000, "onEvent returned promptly (" + elapsedMs + "ms), didn't block");
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "diagnosis completes asynchronously");
            assertEquals(1, store.size());
        }
    }
}
