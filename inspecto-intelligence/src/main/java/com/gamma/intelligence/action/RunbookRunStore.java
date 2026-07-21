package com.gamma.intelligence.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.intelligence.store.AgentWriteRoot;
import com.gamma.intelligence.store.DurableJsonlRing;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Durable progress of seeded-runbook executions (AGT-5 P3, mid-plan resume). Records how far a runbook
 * got for a given {@code (name, params)} so a re-issued run can resume at the failed step instead of
 * re-executing already-succeeded (possibly non-idempotent) steps. Ring mechanics + JSON-lines durability
 * (at {@code <assist.write.root>/agent/runbook-runs.jsonl}) come from {@link DurableJsonlRing}.
 *
 * <p>Keyed by {@code name|canonical(params)} (sorted-key JSON, stable across a round-trip). A
 * <b>terminal</b> run (fully completed) is <em>not</em> resumed — a fresh invocation with the same key
 * runs from the start again; only a <b>halted</b> run yields a resume start-index.
 */
public final class RunbookRunStore extends DurableJsonlRing<RunbookRunStore.RunbookRun> {

    private static final ObjectWriter CANONICAL =
            new ObjectMapper().writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final int DEFAULT_CAPACITY = 512;
    private static final Codec<RunbookRun> CODEC = new Codec<>() {
        @Override public Map<String, Object> toRecord(RunbookRun r) { return r.toRecord(); }
        @Override public RunbookRun fromRecord(Map<String, Object> m) { return RunbookRun.fromRecord(m); }
    };

    /** One runbook-run's progress. {@code completedSteps} is the count of steps that succeeded. */
    public record RunbookRun(String key, String runbook, int completedSteps, boolean terminal, Instant updatedAt) {
        Map<String, Object> toRecord() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("runbook", runbook);
            m.put("completedSteps", completedSteps);
            m.put("terminal", terminal);
            m.put("updatedAt", updatedAt.toString());
            return m;
        }

        static RunbookRun fromRecord(Map<String, Object> m) {
            return new RunbookRun((String) m.get("key"), (String) m.get("runbook"),
                    ((Number) m.get("completedSteps")).intValue(), Boolean.TRUE.equals(m.get("terminal")),
                    Instant.parse((String) m.get("updatedAt")));
        }
    }

    public RunbookRunStore() { this(DEFAULT_CAPACITY, null); }

    public RunbookRunStore(Path file) { this(DEFAULT_CAPACITY, file); }

    RunbookRunStore(int capacity, Path file) { super(capacity, file, CODEC, "runbook run(s)"); }

    /** The most recent record for {@code key}, or empty. */
    public synchronized Optional<RunbookRun> find(String key) {
        RunbookRun latest = null;
        for (RunbookRun r : ring) if (r.key().equals(key)) latest = r; // newest wins (append order)
        return Optional.ofNullable(latest);
    }

    /**
     * The step index (0-based) a run for {@code key} should start at: the halted run's
     * {@code completedSteps}, or 0 when there is no run or the prior run is terminal (fresh start).
     */
    public synchronized int resumeIndex(String key) {
        return find(key).filter(r -> !r.terminal()).map(RunbookRun::completedSteps).orElse(0);
    }

    /** Append a progress record (supersedes prior ones for the same key on read; ring evicts oldest). */
    public void record(String key, String runbook, int completedSteps, boolean terminal) {
        append(new RunbookRun(key, runbook, completedSteps, terminal, Instant.now()));
    }

    /** Canonical key for a runbook name + params (sorted keys → stable across persistence round-trip). */
    public static String key(String runbook, Map<String, Object> params) {
        try {
            String canon = CANONICAL.writeValueAsString(params == null ? Map.of() : params);
            return runbook + "|" + canon;
        } catch (IOException e) {
            return runbook + "|" + params;
        }
    }

    /** Resolve a store from {@code -Dassist.write.root} (durable) or an in-memory one (resume off). */
    public static RunbookRunStore fromWriteRoot() {
        return new RunbookRunStore(AgentWriteRoot.resolve("runbook-runs.jsonl"));
    }
}
