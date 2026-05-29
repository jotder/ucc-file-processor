package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MarkerManager} — duplicate-detection markers, the
 * retention-based stale cleanup, empty-subdir pruning, and the 24h cleanup
 * throttle added in v1.3.2.
 */
class MarkerManagerTest {

    private static PipelineConfig cfg(Path dir) throws Exception {
        return TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).load();
    }

    /** A poll-relative input file under cfg.dirs().poll(). */
    private static File pollFile(PipelineConfig cfg, String rel) throws Exception {
        Path f = Path.of(cfg.dirs().poll(), rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "data");
        return f.toFile();
    }

    @Test
    void isAlreadyProcessedReflectsMarkerPresence(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        File input = pollFile(cfg, "20200403/feed.csv");
        assertFalse(MarkerManager.isAlreadyProcessed(input, cfg), "no marker yet");
        MarkerManager.createMarkerFile(input, cfg);
        assertTrue(MarkerManager.isAlreadyProcessed(input, cfg), "marker now present");
    }

    @Test
    void markerPathMirrorsPollRelativePath(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        File input = pollFile(cfg, "20200403/feed.csv.gz");
        Path marker = MarkerManager.getMarkerPath(input, cfg);
        String m = marker.toString().replace("\\", "/");
        assertTrue(m.contains("/markers/20200403/feed.csv.gz.processed"), m);
    }

    @Test
    void cleanupDeletesStaleMarkersAndPrunesEmptyDirs(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        File oldInput = pollFile(cfg, "20200101/old.csv");
        File newInput = pollFile(cfg, "20200402/new.csv");
        MarkerManager.createMarkerFile(oldInput, cfg);
        MarkerManager.createMarkerFile(newInput, cfg);

        // Age the old marker beyond retention (90 days).
        Path oldMarker = MarkerManager.getMarkerPath(oldInput, cfg);
        Files.setLastModifiedTime(oldMarker,
                FileTime.from(Instant.now().minus(200, ChronoUnit.DAYS)));

        MarkerManager.cleanupStaleMarkers(cfg);

        assertFalse(Files.exists(oldMarker), "stale marker should be deleted");
        assertTrue(Files.exists(MarkerManager.getMarkerPath(newInput, cfg)), "fresh marker kept");
        // The now-empty 20200101 subdir should be pruned.
        assertFalse(Files.exists(Path.of(cfg.dirs().markers(), "20200101")),
                "empty marker subdir should be pruned");
    }

    @Test
    void cleanupIsThrottledWithin24h(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        File seed = pollFile(cfg, "20200402/seed.csv");
        MarkerManager.createMarkerFile(seed, cfg);

        // First cleanup: runs and writes the .last_cleanup sentinel.
        MarkerManager.cleanupStaleMarkers(cfg);
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), ".last_cleanup")), "sentinel written");

        // Create a stale marker AFTER the sentinel exists.
        File oldInput = pollFile(cfg, "20190101/ancient.csv");
        MarkerManager.createMarkerFile(oldInput, cfg);
        Path oldMarker = MarkerManager.getMarkerPath(oldInput, cfg);
        Files.setLastModifiedTime(oldMarker,
                FileTime.from(Instant.now().minus(400, ChronoUnit.DAYS)));

        // Second cleanup within the window is throttled → stale marker survives.
        MarkerManager.cleanupStaleMarkers(cfg);
        assertTrue(Files.exists(oldMarker),
                "throttled cleanup must skip the walk — stale marker should survive");
    }

    @Test
    void disabledDuplicateCheckShortCircuits(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema())
                .duplicateCheck(false).load();
        File input = pollFile(cfg, "20200403/feed.csv");
        assertFalse(MarkerManager.isAlreadyProcessed(input, cfg));
        MarkerManager.createMarkerFile(input, cfg);   // no-op when disabled
        assertFalse(Files.exists(MarkerManager.getMarkerPath(input, cfg)),
                "no marker created when duplicate_check disabled");
    }
}
