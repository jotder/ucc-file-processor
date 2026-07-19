package com.gamma.job;

import com.gamma.event.EventLog;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final ArtifactRecorder artifacts;
    private volatile Map<String, String> params = Map.of();   // resolved by the framework before run (P3a)
    private volatile boolean dryRun;                          // installed by the framework before run (MNT-1)

    RunContext(String runId, String spaceId, String jobName, String trigger, String correlationId,
               int chainDepth, Map<String, String> config, RunLogStore store, int maxEntries,
               RunArtifactStore artifactStore) {
        this.runId     = runId;
        this.spaceId   = spaceId;
        this.trigger   = TriggerInfo.parse(trigger);
        this.config    = config == null ? Map.of() : Map.copyOf(config);
        this.log       = new FileRunLog(runId, store, maxEntries);
        this.signals   = new RunSignalEmitter(new Ref("job", jobName, "emits", "run:" + runId), correlationId, chainDepth);
        this.artifacts = new RunArtifactRecorder(runId, jobName, artifactStore);
    }

    @Override public String runId()               { return runId; }
    @Override public String spaceId()             { return spaceId; }
    @Override public TriggerInfo trigger()        { return trigger; }
    @Override public Map<String, String> config() { return config; }
    @Override public Map<String, String> params() { return params; }
    @Override public RunLog log()                 { return log; }
    @Override public SignalEmitter signals()      { return signals; }
    @Override public ArtifactRecorder artifacts() { return artifacts; }
    @Override public boolean dryRun()              { return dryRun; }

    /** The framework installs the resolved Parameter Context (§7.2) just before {@code Job.run(ctx)}. */
    void params(Map<String, String> resolved) { this.params = Map.copyOf(resolved); }

    /** The framework marks a preview fire (MNT-1) just before {@code Job.run(ctx)}. */
    void dryRun(boolean dryRun) { this.dryRun = dryRun; }

    /** Records each artifact to the run's JSONL, stamping runId/job + a monotonic seq (R7, §10). */
    private static final class RunArtifactRecorder implements ArtifactRecorder {
        private final String runId;
        private final String job;
        private final RunArtifactStore store;
        private final AtomicInteger seq = new AtomicInteger();

        RunArtifactRecorder(String runId, String job, RunArtifactStore store) {
            this.runId = runId;
            this.job = job;
            this.store = store;
        }

        @Override public void dataset(String name, String ref, ResultSetMeta meta, long rows, Instant watermark) {
            store.append(new RunArtifact(runId, job, seq.incrementAndGet(), name, "dataset", ref, meta,
                    rows, 0L, watermark == null ? null : watermark.toString(), null, Instant.now().toString()));
        }

        @Override public void file(String name, java.nio.file.Path path, long bytes) {
            store.append(new RunArtifact(runId, job, seq.incrementAndGet(), name, "file",
                    path == null ? null : path.toString(), null, 0L, bytes, null, null, Instant.now().toString()));
        }
    }

    /** A {@link SignalEmitter} that stamps the envelope (id/time/source/correlation + the run's
     *  {@code chainDepth} into the payload, for loop protection) and routes to the space's ledger via MDC. */
    private static final class RunSignalEmitter implements SignalEmitter {
        private final Ref source;
        private final String correlationId;
        private final int chainDepth;

        RunSignalEmitter(Ref source, String correlationId, int chainDepth) {
            this.source = source;
            this.correlationId = correlationId;
            this.chainDepth = chainDepth;
        }

        @Override public void emit(String type, Severity severity, Map<String, Object> payload) {
            Map<String, Object> p = new LinkedHashMap<>(payload == null ? Map.of() : payload);
            p.putIfAbsent("chainDepth", chainDepth);   // the next run in the chain is chainDepth + 1 (§8.4)
            Signal s = new Signal(null, type, Instant.now(), severity == null ? Severity.INFO : severity,
                    source, null, correlationId, null, null, null, type, p, 1);
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
