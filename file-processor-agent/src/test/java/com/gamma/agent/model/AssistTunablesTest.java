package com.gamma.agent.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tunables resolution (v4.1, B1): property wins, settings-file fallback, bad values ignored. */
class AssistTunablesTest {

    @TempDir
    Path dir;

    @AfterEach
    void cleanup() {
        System.clearProperty("assist.repair.rounds");
        System.clearProperty("assist.confidence.threshold");
        System.clearProperty("assist.settings.file");
    }

    @Test
    void defaultsStandWhenNothingConfigured() {
        System.setProperty("assist.settings.file", dir.resolve("none.properties").toString());
        assertEquals(3, AssistTunables.repairRounds(3));
        assertEquals(0.5, AssistTunables.confidenceThreshold(0.5));
        assertEquals(1024, AssistTunables.reactorQueueCapacity(1024));
    }

    @Test
    void systemPropertyOverrides() {
        System.setProperty("assist.repair.rounds", "5");
        System.setProperty("assist.confidence.threshold", "0.7");
        assertEquals(5, AssistTunables.repairRounds(3));
        assertEquals(0.7, AssistTunables.confidenceThreshold(0.5));
    }

    @Test
    void settingsFileExtraKeysAreTheFallback() throws Exception {
        Path f = dir.resolve("assist.properties");
        Files.writeString(f, "provider=ollama\nassist.repair.rounds=4\n");
        System.setProperty("assist.settings.file", f.toString());
        assertEquals(4, AssistTunables.repairRounds(3));
        // A system property still wins over the file.
        System.setProperty("assist.repair.rounds", "6");
        assertEquals(6, AssistTunables.repairRounds(3));
    }

    @Test
    void garbageAndOutOfRangeValuesFallBack() {
        System.setProperty("assist.repair.rounds", "not-a-number");
        assertEquals(3, AssistTunables.repairRounds(3));
        System.setProperty("assist.confidence.threshold", "7.5");
        assertEquals(0.5, AssistTunables.confidenceThreshold(0.5), "out of [0,1] is ignored");
    }
}
