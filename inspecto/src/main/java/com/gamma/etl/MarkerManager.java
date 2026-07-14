package com.gamma.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Manages duplicate-detection marker files for the ETL pipeline.
 *
 * <p>A marker is a zero-byte sentinel file placed in {@code dirs.markers},
 * mirroring the input file's path relative to {@code dirs.poll}.  Its presence
 * means the file was successfully processed in a previous run and should be
 * skipped now.
 *
 * <p>All methods are stateless — they read configuration from the supplied
 * {@link PipelineConfig} on each call.  Extracted from
 * {@link com.gamma.inspector.CollectorProcessor} where marker logic was spread
 * across four private static methods.
 */
public final class MarkerManager {

    private static final Logger log = LoggerFactory.getLogger(MarkerManager.class);

    private MarkerManager() {}

    // ── duplicate check ───────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a marker file already exists for {@code file},
     * meaning it was processed in a previous run.
     *
     * <p>Always returns {@code false} when {@code duplicate_check.enabled} is
     * {@code false} in the pipeline config.
     */
    public static boolean isAlreadyProcessed(java.io.File file, PipelineConfig cfg) {
        if (!cfg.processing().duplicateCheckEnabled()) return false;
        return Files.exists(getMarkerPath(file, cfg));
    }

    // ── marker creation ───────────────────────────────────────────────────────

    /**
     * Creates a zero-byte marker file for {@code file} in {@code dirs.markers}.
     * Parent directories are created as needed.
     *
     * <p>No-op when {@code duplicate_check.enabled} is {@code false}.
     */
    public static void createMarkerFile(java.io.File file, PipelineConfig cfg)
            throws IOException {
        if (!cfg.processing().duplicateCheckEnabled()) return;
        Path marker = getMarkerPath(file, cfg);
        Files.createDirectories(marker.getParent());
        Files.createFile(marker);
    }

    // ── marker path ───────────────────────────────────────────────────────────

    /**
     * Compute the marker path for an input file inside {@code dirs.markers}.
     *
     * <p>The marker mirrors the file's path relative to {@code dirs.poll}:
     * {@code poll/20200403/feed.csv.gz} → {@code markers/20200403/feed.csv.gz.processed}.
     */
    public static Path getMarkerPath(java.io.File file, PipelineConfig cfg) {
        Path poll     = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path filePath = file.toPath().toAbsolutePath().normalize();
        Path rel      = poll.relativize(filePath);                // e.g. 20200403/feed.csv.gz

        Path base = Paths.get(cfg.dirs().markers()).toAbsolutePath();
        if (rel.getParent() != null) base = base.resolve(rel.getParent());
        return base.resolve(rel.getFileName().toString() + cfg.processing().markerExtension());
    }

    // ── stale marker cleanup ──────────────────────────────────────────────────

    /**
     * Delete marker files older than {@code duplicate_check.retention_days} from
     * {@code dirs.markers}, then prune empty subdirectories left behind.
     *
     * <p>Throttled to run at most once per 24 hours per markers directory.  A
     * sentinel file {@code .last_cleanup} in the markers root records the time of
     * the last run; subsequent polls within the throttle window are no-ops.  This
     * matters at scale — a markers tree with millions of files takes a meaningful
     * time to walk, and there's no reason to do it every poll.  Silently no-ops
     * when markers are disabled or the markers directory does not exist.
     *
     * <p>For an explicit on-demand cleanup (e.g. after deleting retention_days),
     * delete the sentinel and call this method.
     */
    private static final java.time.Duration CLEANUP_INTERVAL = java.time.Duration.ofHours(24);
    private static final String CLEANUP_SENTINEL = ".last_cleanup";

    public static void cleanupStaleMarkers(PipelineConfig cfg) {
        if (cfg.dirs().markers() == null || cfg.dirs().markers().isBlank()) return;
        if (!cfg.processing().duplicateCheckEnabled()) return;

        Path markerRoot = Paths.get(cfg.dirs().markers()).toAbsolutePath();
        if (!Files.exists(markerRoot)) return;

        // Throttle: skip if we ran within CLEANUP_INTERVAL.
        Path sentinel = markerRoot.resolve(CLEANUP_SENTINEL);
        if (Files.exists(sentinel)) {
            try {
                Instant lastRun = Files.getLastModifiedTime(sentinel).toInstant();
                if (lastRun.isAfter(Instant.now().minus(CLEANUP_INTERVAL))) {
                    return;  // throttled; nothing to log
                }
            } catch (IOException ignored) {
                // Couldn't read sentinel — fall through and run cleanup.
            }
        }

        Instant cutoff = Instant.now().minusSeconds((long) cfg.processing().retentionDays() * 86_400L);
        log.info("[MARKER] Cleaning up markers older than {} days in {}",
                cfg.processing().retentionDays(), markerRoot);

        int[] counts = {0, 0};   // [deleted, errors]
        try (Stream<Path> walk = Files.walk(markerRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().equals(CLEANUP_SENTINEL))
                .forEach(marker -> {
                try {
                    if (Files.getLastModifiedTime(marker).toInstant().isBefore(cutoff)) {
                        Files.delete(marker);
                        counts[0]++;
                    }
                } catch (IOException e) {
                    log.warn("[MARKER] Could not check/delete marker {}: {}", marker, e.getMessage());
                    counts[1]++;
                }
            });
        } catch (IOException e) {
            log.warn("[MARKER] Could not walk markers dir {}: {}", markerRoot, e.getMessage());
            return;
        }

        // Prune empty subdirs bottom-up
        try (Stream<Path> walk = Files.walk(markerRoot)) {
            walk.sorted(Comparator.reverseOrder())
                .filter(Files::isDirectory)
                .filter(p -> !p.equals(markerRoot))
                .forEach(dir -> {
                    try { Files.delete(dir); }
                    catch (DirectoryNotEmptyException ignored) {}
                    catch (IOException e) {
                        log.warn("[MARKER] Could not remove marker subdir {}: {}", dir, e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.warn("[MARKER] Could not prune empty marker subdirs: {}", e.getMessage());
        }

        log.info("[MARKER] Cleanup complete — deleted: {}  errors: {}", counts[0], counts[1]);

        // Touch the sentinel so subsequent polls within CLEANUP_INTERVAL skip the walk.
        try {
            if (!Files.exists(sentinel)) Files.createFile(sentinel);
            else Files.setLastModifiedTime(sentinel, java.nio.file.attribute.FileTime.from(Instant.now()));
        } catch (IOException e) {
            log.warn("[MARKER] Could not touch cleanup sentinel {}: {}", sentinel, e.getMessage());
        }
    }
}
