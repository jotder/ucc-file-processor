package com.gamma.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;

/**
 * Shared helper for the virtual-thread + {@link Phaser} fan-out pattern used
 * across all pre-ETL pipeline utilities.
 *
 * <p>The pattern was previously inlined as a private {@code submitTask} method
 * in every class that needed parallel execution:
 * {@link FileOrganizer}, {@link FileBackup}, {@link TarArranger},
 * {@link TarInboxPreparer}, {@link IntegratedProcessor}, and {@link TarExtractor}.
 * This class is the single canonical home.
 *
 * <p>Typical usage:
 * <pre>
 *   ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
 *   Phaser ph = new Phaser(1);          // count = 1 for the orchestrating thread
 *   // ... fan-out ...
 *   VirtualThreadRunner.submit(exec, ph, () -> doWork(arg));
 *   ph.arriveAndAwaitAdvance();          // wait for all submitted tasks to finish
 *   exec.shutdown();
 * </pre>
 */
public final class VirtualThreadRunner {

    private VirtualThreadRunner() {}

    /**
     * Register {@code task} with {@code phaser}, submit it to {@code exec}, and
     * automatically deregister from the phaser when the task finishes (or throws).
     *
     * <p>Any exception thrown by {@code task} is suppressed here — callers should
     * handle errors internally (log, count, etc.) rather than letting them propagate
     * to the executor, which would swallow them silently anyway.
     *
     * @param exec virtual-thread executor to submit to
     * @param ph   phaser tracking all in-flight tasks for this fan-out batch
     * @param task the work to perform; must handle its own exceptions
     */
    public static void submit(ExecutorService exec, Phaser ph, Runnable task) {
        ph.register();
        exec.submit(() -> {
            try   { task.run(); }
            finally { ph.arriveAndDeregister(); }
        });
    }
}
