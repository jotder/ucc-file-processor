package com.gamma.agent.kernel.reason;

import com.gamma.agent.kernel.error.AgentError;
import com.gamma.agent.kernel.error.SystemError;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a task under a wall-clock limit (enforces {@code CapabilitySpec.maxExecutionTime}). On timeout
 * the task is interrupted and a {@link SystemError} is thrown; an {@link AgentError} thrown by the task
 * propagates unchanged; any other failure is wrapped as a {@link SystemError}.
 */
public final class Deadline {

    private Deadline() {}

    public static <T> T call(Duration limit, Callable<T> task) throws AgentError {
        if (limit == null || limit.isZero() || limit.isNegative()) {
            throw new IllegalArgumentException("limit must be positive");
        }
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agentkernel-deadline");
            t.setDaemon(true);
            return t;
        });
        Future<T> future = exec.submit(task);
        try {
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new SystemError("deadline exceeded: " + limit);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AgentError ae) throw ae;
            throw new SystemError("task failed: " + (cause == null ? e : cause.getMessage()), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SystemError("interrupted while awaiting deadline");
        } finally {
            exec.shutdownNow();
        }
    }
}
