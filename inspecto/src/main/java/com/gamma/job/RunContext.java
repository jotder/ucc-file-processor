package com.gamma.job;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The framework's per-Run {@link JobContext} implementation (P0). {@link JobService} populates one
 * just before {@code Job.run(ctx)}, carrying a {@link RunLog} bound to this run's id. Legacy Jobs
 * that implement only the no-arg {@code run()} never observe it (the default bridge on {@link Job}
 * ignores it); it exists so ported Jobs and the framework can log / introspect per Run.
 */
final class RunContext implements JobContext {

    private final String runId;
    private final String spaceId;
    private final TriggerInfo trigger;
    private final Map<String, String> config;
    private final RunLog log;

    RunContext(String runId, String spaceId, String trigger,
               Map<String, String> config, RunLogStore store, int maxEntries) {
        this.runId   = runId;
        this.spaceId = spaceId;
        this.trigger = TriggerInfo.parse(trigger);
        this.config  = config == null ? Map.of() : Map.copyOf(config);
        this.log     = new FileRunLog(runId, store, maxEntries);
    }

    @Override public String runId()               { return runId; }
    @Override public String spaceId()             { return spaceId; }
    @Override public TriggerInfo trigger()        { return trigger; }
    @Override public Map<String, String> config() { return config; }
    @Override public RunLog log()                 { return log; }

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
