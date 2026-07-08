package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Push discovery for local sources (ACQ-6): a {@link WatchService} over each opted-in pipeline's poll root,
 * so a file landing in the inbox triggers a run within ~{@link #QUIET_MS} instead of waiting out the poll
 * interval. Pure JDK, no dependency; the scheduled poll loop stays on as the backstop (watch narrows latency,
 * it does not carry correctness — dedup/stability still decide what is ingested).
 *
 * <p>A source opts in with {@code source.discovery: watch}; only local sources qualify (a remote connector
 * has no local tree to watch — its push analogue is {@code POST /sources/{id}/notify}). Directories created
 * under the root are registered as they appear; an {@link StandardWatchEventKinds#OVERFLOW} simply marks the
 * pipeline dirty (the triggered run is a full scan cycle anyway, so lost events cannot lose files).
 *
 * <p>Events are debounced: a burst of creates/modifies coalesces into one trigger once the tree has been
 * quiet for {@link #QUIET_MS}. Triggers run on this watcher's own thread via the supplied callback
 * (normally {@code SourceService::runPipeline}, which serializes on the ingest lock), so a trigger can never
 * overlap the scheduled cycle.
 */
final class SourceWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SourceWatcher.class);
    private static final long QUIET_MS = Long.getLong("service.watch.quiet.millis", 1000L);

    private final WatchService watchService;
    private final Map<WatchKey, Path> keyDirs = new HashMap<>();          // watcher-thread only after start
    private final Map<Path, String> rootPipelines;                        // poll root → pipeline id
    private final Map<String, Long> dirtySince = new ConcurrentHashMap<>();
    private final Consumer<String> trigger;
    private final Thread loop;
    private volatile boolean closed;

    /**
     * Start a watcher for every registered pipeline with a local {@code source.discovery: watch} source, or
     * return {@code null} when none opts in (the common case — no thread, no OS watch handles).
     */
    static SourceWatcher startFor(List<ConfigRegistry.Entry> entries, Consumer<String> trigger) {
        Map<Path, String> roots = new HashMap<>();
        for (var e : entries) {
            PipelineConfig.Source s = e.config().source();
            if (s == null || !"watch".equals(s.discovery())) continue;
            if (s.hasConnection() || !"local".equals(s.connector())) {
                log.warn("[CONFIG] source '{}' declares discovery=watch but is not local — ignored "
                        + "(remote push = POST /sources/{}/notify)", s.id(), s.id());
                continue;
            }
            roots.put(Paths.get(e.config().dirs().poll()).toAbsolutePath().normalize(), e.id());
        }
        if (roots.isEmpty()) return null;
        try {
            return new SourceWatcher(roots, trigger);
        } catch (IOException ex) {
            log.warn("Could not start filesystem watcher — falling back to interval polling only: {}", ex.getMessage());
            return null;
        }
    }

    private SourceWatcher(Map<Path, String> rootPipelines, Consumer<String> trigger) throws IOException {
        this.rootPipelines = rootPipelines;
        this.trigger = trigger;
        this.watchService = FileSystems.getDefault().newWatchService();
        for (Path root : rootPipelines.keySet()) registerTree(root);
        this.loop = Thread.ofVirtual().name("source-watcher").start(this::run);
        log.info("Push discovery (watch) active for {} source(s): {}", rootPipelines.size(), rootPipelines.values());
    }

    private void run() {
        while (!closed) {
            WatchKey key;
            try {
                // Poll with a bounded wait so debounced triggers fire even when no further events arrive.
                key = watchService.poll(QUIET_MS / 2, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return;
            }
            if (key != null) {
                Path dir = keyDirs.get(key);
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (dir == null) continue;
                    markDirty(dir);
                    // A new subdirectory must itself be watched (WatchService is per-directory).
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && event.context() instanceof Path child) {
                        Path created = dir.resolve(child);
                        if (Files.isDirectory(created)) {
                            try { registerTree(created); } catch (IOException ex) {
                                log.warn("Cannot watch new directory {}: {}", created, ex.getMessage());
                            }
                        }
                    }
                }
                if (!key.reset()) keyDirs.remove(key);   // dir deleted — stop tracking it
            }
            fireQuietPipelines();
        }
    }

    /** Attribute an event under {@code dir} to its owning poll root's pipeline and stamp it dirty. */
    private void markDirty(Path dir) {
        for (Map.Entry<Path, String> r : rootPipelines.entrySet())
            if (dir.startsWith(r.getKey()))
                dirtySince.put(r.getValue(), System.currentTimeMillis());
    }

    /** Trigger every pipeline whose last event is at least {@link #QUIET_MS} old (burst coalescing). */
    private void fireQuietPipelines() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> d : Set.copyOf(dirtySince.entrySet())) {
            if (now - d.getValue() < QUIET_MS) continue;
            dirtySince.remove(d.getKey(), d.getValue());
            log.debug("Watch: triggering '{}' (inbox changed)", d.getKey());
            trigger.accept(d.getKey());
        }
    }

    /** Register {@code root} and every existing subdirectory (create/delete/modify; overflow handled above). */
    private void registerTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) return;   // missing inbox — the poll cycle's create-nothing rule applies
        try (Stream<Path> dirs = Files.walk(root)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                WatchKey key = dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.OVERFLOW);
                keyDirs.put(key, dir);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try { watchService.close(); } catch (IOException e) { log.warn("Error closing watch service: {}", e.getMessage()); }
        try { loop.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
