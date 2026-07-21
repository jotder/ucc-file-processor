package com.gamma.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
     * Returns the underlying {@link ScheduledFuture} — call {@code cancel(false)} to stop just this
     * task without affecting any other schedule or the {@link Scheduler} itself.
     */
    public ScheduledFuture<?> everySeconds(String name, long initialDelaySeconds, long intervalSeconds, Runnable task) {
        return exec.scheduleWithFixedDelay(() -> {
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
     * shutting down. Returns a {@link CronHandle} — call {@link CronHandle#cancel()} to stop
     * this cron's own re-arming chain without affecting any other scheduled job.
     */
    public CronHandle cron(String name, CronExpression cron, ZoneId zone, Runnable task) {
        CronHandle handle = new CronHandle();
        scheduleNextCron(name, cron, zone, task, handle);
        return handle;
    }

    private void scheduleNextCron(String name, CronExpression cron, ZoneId zone, Runnable task, CronHandle handle) {
        if (handle.cancelled.get()) return;
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = cron.next(now);
        long delayMs = Math.max(0, Duration.between(now, next).toMillis());
        try {
            ScheduledFuture<?> future = exec.schedule(() -> {
                try {
                    if (!handle.cancelled.get()) task.run();
                } catch (Exception e) {
                    log.error("Cron task '{}' failed", name, e);
                } finally {
                    if (!exec.isShutdown() && !handle.cancelled.get()) scheduleNextCron(name, cron, zone, task, handle);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            handle.pending.set(future);
            if (handle.cancelled.get()) future.cancel(false);   // cancel() raced ahead of this schedule() call
        } catch (RejectedExecutionException ignore) {
            // scheduler is shutting down — stop re-arming
        }
    }

    /**
     * Cancels one {@link #cron} chain's own re-arming, leaving the {@link Scheduler} and every other
     * job's schedule untouched. Idempotent; cancelling after the final tick already fired is a no-op.
     */
    public static final class CronHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();

        public void cancel() {
            cancelled.set(true);
            ScheduledFuture<?> f = pending.get();
            if (f != null) f.cancel(false);
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
