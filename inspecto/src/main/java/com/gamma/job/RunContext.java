package com.gamma.job;

import com.gamma.event.EventLog;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The framework's per-Run {@link JobContext} implementation (P0/P1). {@link JobService} populates one
 * just before {@code Job.run(ctx)}, carrying a {@link RunLog} bound to this run's id and a
 * {@link SignalEmitter} that stamps identity/time/source/correlation and persists to the one ledger.
 * Legacy Jobs that implement only the no-arg {@code run()} never observe it (the default bridge on
 * {@link Job} ignores it); it exists so ported Jobs and the framework can log, signal, and introspect
 * per Run.
 */
final class RunContext implements JobContext {

    private final String runId;
    private final String spaceId;
    private final TriggerInfo trigger;
    private final Map<String, String> config;
    private final RunLog log;
    private final SignalEmitter signals;

    RunContext(String runId, String spaceId, String jobName, String trigger, String correlationId,
               Map<String, String> config, RunLogStore store, int maxEntries) {
        this.runId   = runId;
        this.spaceId = spaceId;
        this.trigger = TriggerInfo.parse(trigger);
        this.config  = config == null ? Map.of() : Map.copyOf(config);
        this.log     = new FileRunLog(runId, store, maxEntries);
        this.signals = new RunSignalEmitter("job:" + jobName + "/run:" + runId, correlationId);
    }

    @Override public String runId()               { return runId; }
    @Override public String spaceId()             { return spaceId; }
    @Override public TriggerInfo trigger()        { return trigger; }
    @Override public Map<String, String> config() { return config; }
    @Override public RunLog log()                 { return log; }
    @Override public SignalEmitter signals()      { return signals; }

    /** A {@link SignalEmitter} that stamps the envelope and routes to the space's ledger via MDC. */
    private static final class RunSignalEmitter implements SignalEmitter {
        private final String source;
        private final String correlationId;

        RunSignalEmitter(String source, String correlationId) {
            this.source = source;
            this.correlationId = correlationId;
        }

        @Override public void emit(String type, Severity severity, Map<String, Object> payload) {
            Signal s = new Signal(UUID.randomUUID().toString(), type, Instant.now(), source,
                    correlationId, severity == null ? Severity.INFO : severity,
                    payload == null ? Map.of() : payload);
            EventLog.current().emit(s.toEvent());   // MDC (set by the Run) routes to the space store
        }
    }

    /** A {@link RunLog} that appends to the per-run JSONL file, bounded to {@code maxEntries}. */
    private static final class FileRunLog implements RunLog {
        private final String runId;
        private final RunLogStore store;
        private final int maxEntries;
        private final AtomicInteger seq = new AtomicInteger();

        FileRunLog(String runId, RunLogStore store, int maxEntries) {
            this.runId = runId;
            this.store = store;
            this.maxEntries = maxEntries;
        }

        @Override public void info(String message, Object... kv) { emit("INFO", message, kv); }
        @Override public void warn(String message, Object... kv) { emit("WARN", message, kv); }
        @Override public void error(String message, Throwable t, Object... kv) {
            emit("ERROR", t == null ? message : message + ": " + t, kv);
        }

        private void emit(String level, String message, Object... kv) {
            int n = seq.incrementAndGet();
            if (n > maxEntries) {                              // bounded per Run (-Djobs.runlog.maxEntries)
                if (n == maxEntries + 1)
                    store.append(new RunLogEntry(runId, n, Instant.now().toString(), "WARN",
                            "run log truncated at " + maxEntries + " entries", Map.of()));
                return;
            }
            store.append(new RunLogEntry(runId, n, Instant.now().toString(), level, message, kvMap(kv)));
        }

        private static Map<String, Object> kvMap(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
            return m;
        }
    }
}
