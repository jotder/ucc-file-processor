package com.gamma.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    /** Quiet window for "no fire after close()" assertions — must exceed the 1s scheduler granularity. */
    private static final long QUIET_MS = 1200;

    @Test
    void runsTheScheduledTask() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        try (Scheduler s = new Scheduler()) {
            s.everySeconds("t", 0, 1, ran::countDown);
            assertTrue(ran.await(5, TimeUnit.SECONDS), "task should run within 5s");
        }
    }

    @Test
    void survivesAThrowingTaskAndKeepsRunning() throws Exception {
        // A second fire can only happen if the schedule survived the first task's throw, so
        // count down to 2 and return the instant it does — no fixed oversleep. The 5s await is
        // only a ceiling for a loaded CI box; in practice this completes in ~1s (two 1s ticks).
        CountDownLatch ranTwice = new CountDownLatch(2);
        try (Scheduler s = new Scheduler()) {
            s.everySeconds("t", 0, 1, () -> {
                ranTwice.countDown();
                throw new RuntimeException("boom");
            });
            assertTrue(ranTwice.await(5, TimeUnit.SECONDS),
                    "schedule must survive a throwing task and fire at least twice");
        }
    }

    @Test
    void cronFiresAndReArms() throws Exception {
        // every-second cron should fire repeatedly via the self-re-arming path
        CountDownLatch fired = new CountDownLatch(2);
        try (Scheduler s = new Scheduler()) {
            s.cron("c", CronExpression.parse("* * * * * *"), ZoneId.systemDefault(), fired::countDown);
            assertTrue(fired.await(5, TimeUnit.SECONDS), "cron should fire at least twice within 5s");
        }
    }

    @Test
    void cronStopsAfterClose() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstFire = new CountDownLatch(1);
        Scheduler s = new Scheduler();
        s.cron("c", CronExpression.parse("* * * * * *"), ZoneId.systemDefault(), () -> {
            runs.incrementAndGet();
            firstFire.countDown();
        });
        assertTrue(firstFire.await(5, TimeUnit.SECONDS), "cron should fire at least once before close()");
        s.close();
        int after = runs.get();
        // Quiet period must exceed the 1s cron granularity to be meaningful; 1200ms proves no re-arm.
        Thread.sleep(QUIET_MS);
        assertEquals(after, runs.get(), "no further cron fires after close()");
    }

    @Test
    void closeStopsFurtherRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstRun = new CountDownLatch(1);
        Scheduler s = new Scheduler();
        s.everySeconds("t", 0, 1, () -> {
            runs.incrementAndGet();
            firstRun.countDown();
        });
        assertTrue(firstRun.await(5, TimeUnit.SECONDS), "task should run at least once before close()");
        s.close();
        int after = runs.get();
        Thread.sleep(QUIET_MS);
        assertEquals(after, runs.get(), "no further runs after close()");
    }
}
