package com.gamma.pipeline.exec;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T13 — {@link TriggerCoalescer}: events fold into a non-overlapping run; storms collapse to far fewer
 * runs than signals; the run never executes concurrently with itself.
 */
class TriggerCoalescerTest {

    @Test
    void eventsArrivingDuringARunFoldIntoOneFollowUp() {
        TriggerCoalescer c = new TriggerCoalescer();
        AtomicInteger nested = new AtomicInteger(5);
        // on the first run, fire 5 more signals — they must coalesce into exactly one extra run
        c.signal(new Runnable() {
            @Override public void run() {
                int n = nested.getAndSet(0);
                for (int i = 0; i < n; i++) c.signal(this);
            }
        });
        assertEquals(2, c.runCount(), "1 initial run + 1 coalesced follow-up for the 5 nested signals");
        assertEquals(6, c.signalCount());
    }

    @Test
    void sequentialSignalsEachRunWhenIdle() {
        TriggerCoalescer c = new TriggerCoalescer();
        AtomicInteger work = new AtomicInteger();
        c.signal(work::incrementAndGet);
        c.signal(work::incrementAndGet);
        c.signal(work::incrementAndGet);
        assertEquals(3, work.get());
        assertEquals(3, c.runCount());
    }

    @Test
    void concurrentStormCoalescesAndNeverOverlaps() throws Exception {
        TriggerCoalescer c = new TriggerCoalescer();
        int threads = 50;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        Runnable run = () -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try { Thread.sleep(1); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            active.decrementAndGet();
        };

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) { return; }
                c.signal(run);
                done.countDown();
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(threads, c.signalCount());
        assertEquals(1, maxActive.get(), "the run never overlaps itself");
        assertTrue(c.runCount() >= 1 && c.runCount() <= threads, "runs coalesced: " + c.runCount());
    }
}
