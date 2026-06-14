package com.gamma.acquire.retry;

import com.gamma.etl.PipelineConfig;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * A small, dependency-free retry/backoff helper for transient acquisition faults (Data Acquisition roadmap
 * Phase F). The engine wraps connectivity-sensitive connector operations — {@code discover}, per-file
 * {@code fetchTo} — so a flaky SFTP/FTP endpoint gets a bounded set of retries with backoff instead of failing
 * the whole poll cycle on the first hiccup.
 *
 * <h3>Backoff</h3>
 * The base delay before the <em>n</em>th retry (0-indexed) is grown from {@code initialDelay} towards
 * {@code maxDelay} by the configured {@link Backoff} curve, then <b>full-jittered</b> — the actual sleep is a
 * uniform random in {@code [0, base]} — to avoid a thundering herd of pollers retrying in lockstep. Delays are
 * always clamped to {@code maxDelay}.
 *
 * <h3>Testability</h3>
 * The sleep is injectable ({@link Sleeper}) and the jitter source is overridable, so a test can assert the delay
 * schedule deterministically without wall-clock waits. {@link #NONE} performs exactly one attempt (today's
 * behaviour) and never sleeps.
 */
public final class RetryPolicy {

    /** A unit of retriable work that may throw. */
    @FunctionalInterface
    public interface Op<T> {
        T run() throws Exception;
    }

    /** Pluggable sleep so tests need not wait on a real clock. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** How the base delay grows across successive retries. */
    public enum Backoff {
        /** {@code initial * 2^attempt}. */ EXPONENTIAL,
        /** {@code initial * (attempt + 1)}. */ LINEAR,
        /** {@code initial} every time. */ FIXED;

        public static Backoff from(String s) {
            if (s == null) return EXPONENTIAL;
            return switch (s.trim().toUpperCase()) {
                case "LINEAR" -> LINEAR;
                case "FIXED", "CONSTANT" -> FIXED;
                default -> EXPONENTIAL;
            };
        }
    }

    /** A policy that runs the operation exactly once — no retry, no sleep. */
    public static final RetryPolicy NONE = new RetryPolicy(0, Backoff.FIXED, 0L, 0L, ms -> {}, b -> 0L);

    private final int retries;
    private final Backoff backoff;
    private final long initialMillis;
    private final long maxMillis;
    private final Sleeper sleeper;
    private final LongUnaryOperator jitter;   // base → actual sleep (full jitter in [0, base] by default)

    RetryPolicy(int retries, Backoff backoff, long initialMillis, long maxMillis,
                Sleeper sleeper, LongUnaryOperator jitter) {
        this.retries = Math.max(0, retries);
        this.backoff = backoff;
        this.initialMillis = Math.max(0L, initialMillis);
        this.maxMillis = Math.max(this.initialMillis, maxMillis);
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    /** Build from a parsed {@code source.retry:} block, using real sleeps + full jitter. */
    public static RetryPolicy from(PipelineConfig.Retry cfg) {
        if (cfg == null || !cfg.enabled()) return NONE;
        return new RetryPolicy(cfg.count(), Backoff.from(cfg.backoff()),
                cfg.initialDelayMillis(), cfg.maxDelayMillis(),
                Thread::sleep,
                base -> base <= 0 ? 0L : ThreadLocalRandom.current().nextLong(base + 1));
    }

    /** Test seam: a deterministic policy with an injected sleeper and a no-jitter (sleep == base) curve. */
    public static RetryPolicy forTest(int retries, Backoff backoff, long initialMillis, long maxMillis,
                                      Sleeper sleeper) {
        return new RetryPolicy(retries, backoff, initialMillis, maxMillis, sleeper, LongUnaryOperator.identity());
    }

    /** Total attempts this policy will make (1 + retries). */
    public int attempts() {
        return retries + 1;
    }

    /**
     * Run {@code op}, retrying up to {@link #attempts()} total times on any thrown exception. The exception from
     * the final attempt is rethrown. An {@link InterruptedException} during a backoff sleep restores the interrupt
     * flag and rethrows immediately (no further attempts).
     */
    public <T> T execute(Op<T> op) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return op.run();
            } catch (Exception e) {
                last = e;
                if (attempt == retries) break;          // out of retries — surface the failure
                long delay = jitter.applyAsLong(baseDelayMillis(attempt));
                if (delay > 0) sleeper.sleep(delay);
            }
        }
        throw last;
    }

    /** The (pre-jitter) base delay before the retry that follows a failed {@code attempt} (0-indexed). */
    long baseDelayMillis(int attempt) {
        long raw = switch (backoff) {
            case EXPONENTIAL -> {
                // initial * 2^attempt, saturating rather than overflowing on a long run of failures.
                long shifted = (attempt >= 62) ? Long.MAX_VALUE : initialMillis << attempt;
                yield shifted;
            }
            case LINEAR -> initialMillis * (attempt + 1L);
            case FIXED  -> initialMillis;
        };
        return Math.min(maxMillis, Math.max(0L, raw));
    }
}
