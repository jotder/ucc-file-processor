package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory, thread-safe index of loaded pipeline configs keyed by their <b>in-file identity</b>
 * ({@code config.identity().pipelineName()}) — the fix for the O(n) re-parse scans that
 * {@code SourceService.pathFor}/{@code configFor}/{@code activeRegistry} performed on every call.
 *
 * <p>Each {@link #rebuild} loads every path <em>once</em> and snapshots the result, so subsequent
 * lookups are O(1) map reads with no disk I/O. It also resolves the "discovery-suffix vs in-file
 * identity" mismatch the old code never reconciled: discovery finds files by the {@code _pipeline.toon}
 * suffix, but lookups address pipelines by the {@code name} declared <em>inside</em> the file — so
 * keying the index by that in-file identity makes a name lookup correct regardless of filename, and
 * two files claiming the same identity are flagged rather than silently shadowing each other.
 *
 * <p>This index backs the <b>read</b> surface only (the Control API, the catalog's
 * {@code ConfigSource}, status sync). The ingest run path still operates on the raw registry
 * <em>paths</em> and re-loads each cycle (fresh run timestamp, picks up edits), so a cached config's
 * frozen timestamp never affects a run — and the audit reads resolve by the stable status directory
 * + pipeline name + persistent commit log, never by that timestamp.
 */
public final class ConfigRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConfigRegistry.class);
    private static final String PIPELINE_SUFFIX = "_pipeline.toon";

    /** One indexed pipeline: its stable id, source path, and the loaded config. */
    public record Entry(String id, Path path, PipelineConfig config) {}

    /** Snapshot map (id → entry); replaced wholesale on {@link #rebuild} for lock-free reads. */
    private final AtomicReference<Map<String, Entry>> index = new AtomicReference<>(Map.of());
    private final Runnable onRebuild;

    public ConfigRegistry() {
        this(null);
    }

    /**
     * @param onRebuild a callback fired after every successful {@link #rebuild} (e.g. to invalidate a
     *                  derived catalog when configs change); {@code null} for none
     */
    public ConfigRegistry(Runnable onRebuild) {
        this.onRebuild = onRebuild;
    }

    /**
     * Load every path once and replace the index with the result. Unloadable configs are warned and
     * skipped (matching the prior behaviour); a duplicate in-file identity warns and the later path
     * wins. Fires the rebuild callback on completion.
     */
    public void rebuild(List<Path> paths) {
        Map<String, Entry> next = new LinkedHashMap<>();
        for (Path p : paths) {
            try {
                PipelineConfig cfg = PipelineConfig.load(p.toString());
                String id = cfg.identity().pipelineName();
                noteSuffixDivergence(p, id);
                Entry prev = next.put(id, new Entry(id, p, cfg));
                if (prev != null) {
                    log.warn("Duplicate pipeline id '{}' from {} and {}; keeping the latter",
                            id, prev.path(), p);
                }
            } catch (Exception e) {
                log.warn("Could not load config {}: {}", p, e.getMessage());
            }
        }
        index.set(Map.copyOf(next));
        if (onRebuild != null) {
            onRebuild.run();
        }
    }

    /** The config for a pipeline id, if indexed. */
    public Optional<PipelineConfig> get(String id) {
        Entry e = index.get().get(id);
        return e == null ? Optional.empty() : Optional.of(e.config());
    }

    /** The source path for a pipeline id, if indexed. */
    public Optional<Path> getPath(String id) {
        Entry e = index.get().get(id);
        return e == null ? Optional.empty() : Optional.of(e.path());
    }

    /** The pipeline id registered for {@code path}, if any (reverse lookup; O(n) but n is small). */
    public Optional<String> idForPath(Path path) {
        return index.get().values().stream()
                .filter(e -> e.path().equals(path))
                .map(Entry::id)
                .findFirst();
    }

    /** All loaded configs in registration order. */
    public List<PipelineConfig> configs() {
        return index.get().values().stream().map(Entry::config).toList();
    }

    /** All indexed entries in registration order. */
    public List<Entry> all() {
        return List.copyOf(index.get().values());
    }

    public int size() {
        return index.get().size();
    }

    /**
     * Log (at INFO, not WARN) when a file's {@code _pipeline.toon} prefix differs from the in-file
     * identity. A differing prefix is a common, legitimate pattern (the file name need not equal the
     * pipeline name), so this is visibility — not an alarm — confirming which identity won.
     */
    private static void noteSuffixDivergence(Path p, String id) {
        String file = p.getFileName().toString();
        if (!file.endsWith(PIPELINE_SUFFIX)) {
            return;
        }
        String prefix = file.substring(0, file.length() - PIPELINE_SUFFIX.length());
        if (!prefix.equalsIgnoreCase(id)) {
            log.info("Config {} registered under in-file id '{}' (filename prefix is '{}')", p, id, prefix);
        }
    }
}
