package com.gamma.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Minimal interval scheduler for the service. Runs tasks with a fixed delay between
 * the end of one execution and the start of the next ({@code scheduleWithFixedDelay}),
 * so polls never overlap. A task that throws is logged and the schedule continues.
 *
 * <p>This M1 cut is interval-based (e.g. "poll every 60s"), which covers continuous
 * polling. Calendar/cron scheduling (specific hour/day for Stage-2 windowed KPIs) is
 * a fast-follow in M2; the small daemon-threaded executor here is the trigger, while
 * the actual work runs on virtual threads inside the pipeline runners.
 */
public final class Scheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final ScheduledExecutorService exec;

    public Scheduler() {
        this.exec = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ucc-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Run {@code task} every {@code intervalSeconds}, starting after
     * {@code initialDelaySeconds}. Exceptions are caught so the schedule survives.
     */
    public void everySeconds(String name, long initialDelaySeconds, long intervalSeconds, Runnable task) {
        exec.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Scheduled task '{}' failed", name, e);
            }
        }, initialDelaySeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        exec.shutdownNow();
        try {
            if (!exec.awaitTermination(30, TimeUnit.SECONDS))
                log.warn("Scheduler did not terminate within 30s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
