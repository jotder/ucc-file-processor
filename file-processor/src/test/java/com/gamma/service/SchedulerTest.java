package com.gamma.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

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
        AtomicInteger runs = new AtomicInteger();
        try (Scheduler s = new Scheduler()) {
            s.everySeconds("t", 0, 1, () -> {
                runs.incrementAndGet();
                throw new RuntimeException("boom");
            });
            Thread.sleep(2500);   // ~3 fixed-delay executions
            assertTrue(runs.get() >= 2, "schedule must survive a throwing task, got " + runs.get());
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
        Scheduler s = new Scheduler();
        s.cron("c", CronExpression.parse("* * * * * *"), ZoneId.systemDefault(), runs::incrementAndGet);
        Thread.sleep(2200);
        s.close();
        int after = runs.get();
        Thread.sleep(2000);
        assertEquals(after, runs.get(), "no further cron fires after close()");
    }

    @Test
    void closeStopsFurtherRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Scheduler s = new Scheduler();
        s.everySeconds("t", 0, 1, runs::incrementAndGet);
        Thread.sleep(1200);
        s.close();
        int after = runs.get();
        Thread.sleep(1500);
        assertEquals(after, runs.get(), "no further runs after close()");
    }
}
