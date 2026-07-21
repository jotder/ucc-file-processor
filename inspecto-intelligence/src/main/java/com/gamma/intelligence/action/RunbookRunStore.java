package com.gamma.intelligence.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Durable progress of seeded-runbook executions (AGT-5 P3, mid-plan resume). Records how far a runbook
 * got for a given {@code (name, params)} so a re-issued run can resume at the failed step instead of
 * re-executing already-succeeded (possibly non-idempotent) steps. Mirrors {@code ApprovalStore}'s
 * idiom: a bounded JSON-lines ring at {@code <assist.write.root>/agent/runbook-runs.jsonl}, loaded at
 * construction and rewritten on every {@link #record}; in-memory only (resume disabled) without a write
 * root, so behaviour is unchanged from the pre-resume cut in dev/tests. Persistence failures degrade to
 * in-memory (log + continue).
 *
 * <p>Keyed by {@code name|canonical(params)} (sorted-key JSON, stable across a round-trip). A
 * <b>terminal</b> run (fully completed) is <em>not</em> resumed — a fresh invocation with the same key
 * runs from the start again; only a <b>halted</b> run yields a resume start-index.
 */
public final class RunbookRunStore {

    private static final Logger log = LoggerFactory.getLogger(RunbookRunStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectWriter CANONICAL = JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final int DEFAULT_CAPACITY = 512;

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

    private final int capacity;
    private final Path file; // null → in-memory only
    private final Deque<RunbookRun> ring = new ArrayDeque<>();

    public RunbookRunStore() { this(DEFAULT_CAPACITY, null); }

    public RunbookRunStore(Path file) { this(DEFAULT_CAPACITY, file); }

    RunbookRunStore(int capacity, Path file) {
        this.capacity = capacity;
        this.file = file;
        load();
    }

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
    public synchronized void record(String key, String runbook, int completedSteps, boolean terminal) {
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(new RunbookRun(key, runbook, completedSteps, terminal, Instant.now()));
        persist();
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

    public synchronized int size() { return ring.size(); }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Map<String, Object> rec = JSON.readValue(line, new TypeReference<Map<String, Object>>() {});
                if (ring.size() >= capacity) ring.removeFirst();
                ring.addLast(RunbookRun.fromRecord(rec));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not load persisted runbook runs from {}: {}", file, e.getMessage());
            ring.clear();
        }
    }

    private void persist() {
        if (file == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (RunbookRun r : ring) sb.append(JSON.writeValueAsString(r.toRecord())).append('\n');
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not persist runbook runs to {}: {}", file, e.getMessage());
        }
    }

    /** Resolve a store from {@code -Dassist.write.root} (durable) or an in-memory one (resume off). */
    public static RunbookRunStore fromWriteRoot() {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank()
                ? new RunbookRunStore()
                : new RunbookRunStore(Path.of(wr).resolve("agent").resolve("runbook-runs.jsonl"));
    }
}
