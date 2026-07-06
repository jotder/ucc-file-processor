package com.gamma.agent.model;

import com.gamma.agent.kernel.error.ModelError;
import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelResponse;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Hard-deadline decorator over a {@link ModelProvider} (v4.1, B1 hardening). Capability specs have
 * always <em>declared</em> a timeout, but nothing enforced it — a hung provider could stall an
 * assist call indefinitely. This wraps {@link #generate} in a future on a shared daemon
 * virtual-thread executor and fails with a clean {@link ModelError} after the deadline; the
 * orchestrator then surfaces the usual UNAVAILABLE instead of hanging the HTTP request.
 *
 * <p>The underlying call is interrupted on timeout (best effort — HTTP clients honour it), and the
 * decorator passes {@link #name()} / {@link #available()} through untouched.
 */
public final class TimeoutModelProvider implements ModelProvider {

    /** Shared, daemon, vthread-per-task: never keeps the JVM alive, scales with concurrent calls. */
    private static final ExecutorService POOL = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("assist-model-timeout-", 0).factory());

    private final ModelProvider delegate;
    private final Duration timeout;

    private TimeoutModelProvider(ModelProvider delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    /** Wrap a provider with a hard deadline; a non-positive timeout returns the provider unwrapped. */
    public static ModelProvider wrap(ModelProvider delegate, Duration timeout) {
        if (delegate == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
            return delegate;
        }
        return new TimeoutModelProvider(delegate, timeout);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        Future<ModelResponse> f = POOL.submit(() -> delegate.generate(request));
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new ModelError("model call timed out after " + timeout.toSeconds() + "s ("
                    + delegate.name() + ")");
        } catch (ExecutionException e) {
            throw (e.getCause() instanceof ModelError me)
                    ? me : new ModelError("model call failed (" + delegate.name() + ")", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw new ModelError("model call interrupted (" + delegate.name() + ")");
        }
    }
}
