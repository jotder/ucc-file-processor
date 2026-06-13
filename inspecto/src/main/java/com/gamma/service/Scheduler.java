package com.gamma.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Minimal interval scheduler for the service. Runs tasks with a fixed delay between
 * the end of one execution and the start of the next ({@code scheduleWithFixedDelay}),
 * so polls never overlap. A task that throws is logged and the schedule continues.
 *
 * <p>Two trigger styles are offered:
 * <ul>
 *   <li>{@link #everySeconds} — fixed-delay interval (e.g. "poll every 60s"), for
 *       continuous polling. Runs never overlap.</li>
 *   <li>{@link #cron} — calendar scheduling via a {@link CronExpression} (e.g. "daily
 *       at 02:00", "every weekday at 09:00"), for config-driven jobs and windowed
 *       Stage-2 KPIs. Implemented as a self-re-arming one-shot: after each fire it
 *       computes the next fire time and reschedules.</li>
 * </ul>
 *
 * <p>The executor here is only the <em>trigger</em> — handlers are expected to be
 * short (submit work to a virtual-thread executor and return), so the small daemon
 * pool never becomes a bottleneck. A task that throws is logged and the schedule
 * continues.
 */
public final class Scheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final ScheduledExecutorService exec;

    public Scheduler() {
        // A couple of trigger threads so a cron fire isn't head-of-line blocked behind a
        // long fixed-delay poll. The real work runs off these threads (virtual-thread pools).
        this.exec = Executors.newScheduledThreadPool(2, new java.util.concurrent.ThreadFactory() {
            private int n = 0;
            @Override public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "inspecto-scheduler-" + (++n));
                t.setDaemon(true);
                return t;
            }
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

    /**
     * Run {@code task} on the calendar schedule described by {@code cron}, evaluated in
     * {@code zone}. The first fire is the next match strictly after "now"; after each run
     * the next fire is recomputed and rescheduled, so drift never accumulates. Exceptions
     * are caught so the schedule survives, and re-arming is skipped once the scheduler is
     * shutting down.
     */
    public void cron(String name, CronExpression cron, ZoneId zone, Runnable task) {
        scheduleNextCron(name, cron, zone, task);
    }

    private void scheduleNextCron(String name, CronExpression cron, ZoneId zone, Runnable task) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = cron.next(now);
        long delayMs = Math.max(0, Duration.between(now, next).toMillis());
        try {
            exec.schedule(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("Cron task '{}' failed", name, e);
                } finally {
                    if (!exec.isShutdown()) scheduleNextCron(name, cron, zone, task);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignore) {
            // scheduler is shutting down — stop re-arming
        }
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
