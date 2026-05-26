package com.gamma.etl;

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
 * {@link com.gamma.inspector.SourceProcessor} where marker logic was spread
 * across four private static methods.
 */
public final class MarkerManager {

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
        if (!cfg.duplicateCheckEnabled) return false;
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
        if (!cfg.duplicateCheckEnabled) return;
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
        Path poll     = Paths.get(cfg.pollDir).toAbsolutePath().normalize();
        Path filePath = file.toPath().toAbsolutePath().normalize();
        Path rel      = poll.relativize(filePath);                // e.g. 20200403/feed.csv.gz

        Path base = Paths.get(cfg.markersDir).toAbsolutePath();
        if (rel.getParent() != null) base = base.resolve(rel.getParent());
        return base.resolve(rel.getFileName().toString() + cfg.markerExtension);
    }

    // ── stale marker cleanup ──────────────────────────────────────────────────

    /**
     * Delete marker files older than {@code duplicate_check.retention_days} from
     * {@code dirs.markers}, then prune empty subdirectories left behind.
     *
     * <p>Runs once at the start of each poll cycle so the markers directory does
     * not grow unbounded.  Silently no-ops when markers are disabled or the
     * markers directory does not exist.
     */
    public static void cleanupStaleMarkers(PipelineConfig cfg) {
        if (cfg.markersDir == null || cfg.markersDir.isBlank()) return;
        if (!cfg.duplicateCheckEnabled) return;

        Path markerRoot = Paths.get(cfg.markersDir).toAbsolutePath();
        if (!Files.exists(markerRoot)) return;

        Instant cutoff = Instant.now().minusSeconds((long) cfg.retentionDays * 86_400L);
        System.out.printf("[MARKER] Cleaning up markers older than %d days in %s%n",
                cfg.retentionDays, markerRoot);

        int[] counts = {0, 0};   // [deleted, errors]
        try (Stream<Path> walk = Files.walk(markerRoot)) {
            walk.filter(Files::isRegularFile).forEach(marker -> {
                try {
                    if (Files.getLastModifiedTime(marker).toInstant().isBefore(cutoff)) {
                        Files.delete(marker);
                        counts[0]++;
                    }
                } catch (IOException e) {
                    System.err.printf("[WARN] Could not check/delete marker %s: %s%n",
                            marker, e.getMessage());
                    counts[1]++;
                }
            });
        } catch (IOException e) {
            System.err.printf("[WARN] Could not walk markers dir %s: %s%n",
                    markerRoot, e.getMessage());
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
                        System.err.printf("[WARN] Could not remove marker subdir %s: %s%n",
                                dir, e.getMessage());
                    }
                });
        } catch (IOException e) {
            System.err.printf("[WARN] Could not prune empty marker subdirs: %s%n", e.getMessage());
        }

        System.out.printf("[MARKER] Cleanup complete — deleted: %d  errors: %d%n",
                counts[0], counts[1]);
    }
}
