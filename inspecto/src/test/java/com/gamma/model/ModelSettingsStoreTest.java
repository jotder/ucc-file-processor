package com.gamma.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the S9 core-owned model-settings bridge: {@link ModelSettingsStore} reads the same
 * assist-settings.properties the agent module's settings screen writes, and {@link ModelSettings}'s
 * defaults/local/tier helpers — the read path {@code inspecto-intelligence} now uses instead of a
 * compile dep on {@code inspecto-agent}.
 */
class ModelSettingsStoreTest {

    private static final String PROP = "assist.settings.file";

    @Test
    void loadReadsProviderTierMapAndTimeout(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("assist-settings.properties");
        Files.writeString(file, """
                provider=ollama
                base.url=http://localhost:11434
                api.key.ref=SOME_REF
                model.small=qwen2.5:3b
                model.medium=qwen2.5:7b
                model.large=qwen2.5:14b
                timeout.seconds=90
                """);
        withSettingsFile(file, () -> {
            ModelSettings s = ModelSettingsStore.load().orElseThrow();
            assertEquals("ollama", s.provider());
            assertEquals("http://localhost:11434", s.baseUrl());
            assertEquals("SOME_REF", s.apiKeyRef());
            assertEquals("qwen2.5:7b", s.model("medium"));
            assertTrue(s.local());
            assertEquals(90, s.timeoutSeconds());
        });
    }

    @Test
    void loadIsEmptyWithoutAFileOrProvider(@TempDir Path dir) throws Exception {
        withSettingsFile(dir.resolve("nope.properties"), () -> assertTrue(ModelSettingsStore.load().isEmpty()));

        Path noProvider = dir.resolve("empty.properties");
        Files.writeString(noProvider, "timeout.seconds=30\n");
        withSettingsFile(noProvider, () -> assertTrue(ModelSettingsStore.load().isEmpty()));
    }

    @Test
    void defaultsWireLocalProvidersOnly() {
        ModelSettings ollama = ModelSettings.defaults("ollama");
        assertTrue(ollama.local());
        assertEquals("http://localhost:11434", ollama.baseUrl());
        assertEquals("qwen2.5:7b", ollama.model("MEDIUM"));   // tier lookup is case-insensitive

        ModelSettings hosted = ModelSettings.defaults("anthropic");
        assertFalse(hosted.local());
        assertNull(hosted.baseUrl());
        assertNull(hosted.model("medium"));

        ModelSettings blank = ModelSettings.defaults(null);
        assertEquals("", blank.provider());
        assertEquals(60, blank.timeoutSeconds());   // non-positive timeout normalizes to the default
    }

    private interface Body { void run() throws Exception; }

    /** Point the store at {@code file} for the duration of {@code body}, restoring the prior value after. */
    private static void withSettingsFile(Path file, Body body) throws Exception {
        String prev = System.getProperty(PROP);
        System.setProperty(PROP, file.toString());
        try {
            body.run();
        } finally {
            if (prev == null) System.clearProperty(PROP);
            else System.setProperty(PROP, prev);
        }
    }
}
