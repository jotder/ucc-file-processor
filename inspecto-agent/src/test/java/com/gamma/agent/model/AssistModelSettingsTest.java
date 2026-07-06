package com.gamma.agent.model;

import com.gamma.agent.kernel.model.ModelTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings persistence (v4.1): round-trip, secret hygiene, and key resolution order. */
class AssistModelSettingsTest {

    @TempDir
    Path dir;

    @AfterEach
    void cleanup() {
        System.clearProperty("assist.settings.file");
        System.clearProperty("TEST_ASSIST_KEY_REF");
        AssistModelSettings.setSessionKey("anthropic", null);
    }

    private void useFile(String name) {
        System.setProperty("assist.settings.file", dir.resolve(name).toString());
    }

    @Test
    void roundTripsSettings() {
        useFile("assist.properties");
        ProviderSettings in = new ProviderSettings("anthropic", null, "ANTHROPIC_API_KEY",
                Map.of(ModelTier.SMALL, "claude-haiku-4-5", ModelTier.MEDIUM, "claude-sonnet-4-6",
                        ModelTier.LARGE, "claude-opus-4-8"), 45);
        AssistModelSettings.save(in);
        ProviderSettings out = AssistModelSettings.load().orElseThrow();
        assertEquals("anthropic", out.provider());
        assertEquals("ANTHROPIC_API_KEY", out.apiKeyRef());
        assertEquals("claude-sonnet-4-6", out.model(ModelTier.MEDIUM));
        assertEquals(45, out.timeoutSeconds());
        assertNull(out.baseUrl());
    }

    @Test
    void neverPersistsAnApiKey() throws Exception {
        useFile("assist.properties");
        AssistModelSettings.setSessionKey("anthropic", "sk-ant-SECRET-VALUE");
        AssistModelSettings.save(ProviderSettings.defaults("anthropic"));
        String onDisk = Files.readString(AssistModelSettings.path());
        assertFalse(onDisk.contains("SECRET-VALUE"), "raw key must never reach disk");
        assertTrue(onDisk.contains("ANTHROPIC_API_KEY"), "only the key REF is persisted");
    }

    @Test
    void missingFileMeansEmpty() {
        useFile("does-not-exist.properties");
        assertTrue(AssistModelSettings.load().isEmpty());
    }

    @Test
    void resolvesSessionKeyBeforeReference() {
        ProviderSettings s = new ProviderSettings("anthropic", null, "TEST_ASSIST_KEY_REF",
                Map.of(), 0);
        assertNull(AssistModelSettings.resolveApiKey(s), "nothing configured yet");
        System.setProperty("TEST_ASSIST_KEY_REF", "from-property");
        assertEquals("from-property", AssistModelSettings.resolveApiKey(s));
        AssistModelSettings.setSessionKey("anthropic", "from-session");
        assertEquals("from-session", AssistModelSettings.resolveApiKey(s),
                "a key submitted over the API wins over the env reference");
    }
}
