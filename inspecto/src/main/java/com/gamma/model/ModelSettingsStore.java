package com.gamma.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Core-owned reader for {@link ModelSettings} (S9), reading the small properties file the assist-settings
 * screen writes ({@code -Dassist.settings.file}, default {@code config/assist-settings.properties}):
 * <pre>
 *   provider=ollama
 *   base.url=http://localhost:11434
 *   api.key.ref=ANTHROPIC_API_KEY   (env var / system-property NAME — never the key)
 *   model.small=…  model.medium=…  model.large=…
 *   timeout.seconds=60
 * </pre>
 *
 * <p>The <em>writer</em> of this format lives in the agent module ({@code AssistModelSettings#save}, behind
 * the assist-settings screen); this read-only twin lets {@code inspecto-intelligence} load the same
 * settings without compile-depending on {@code inspecto-agent}. The file path property and key names are the
 * shared contract between the two — keep them in sync with {@code AssistModelSettings}.
 */
public final class ModelSettingsStore {

    private static final String PROP_FILE = "assist.settings.file";
    private static final String DEFAULT_FILE = "config/assist-settings.properties";
    private static final String[] TIERS = {"small", "medium", "large"};

    private ModelSettingsStore() {}

    /** The settings file path ({@code -Dassist.settings.file}, default {@value #DEFAULT_FILE}). */
    public static Path path() {
        String p = System.getProperty(PROP_FILE);
        return Path.of(p == null || p.isBlank() ? DEFAULT_FILE : p);
    }

    /** Load persisted settings; empty when no file exists or it names no provider. */
    public static Optional<ModelSettings> load() {
        Path file = path();
        if (!Files.isRegularFile(file)) return Optional.empty();
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read model settings " + file, e);
        }
        String provider = props.getProperty("provider", "").trim();
        if (provider.isEmpty()) return Optional.empty();
        Map<String, String> models = new LinkedHashMap<>();
        for (String t : TIERS) {
            String m = props.getProperty("model." + t);
            if (m != null && !m.isBlank()) models.put(t, m.trim());
        }
        int timeout;
        try {
            timeout = Integer.parseInt(props.getProperty("timeout.seconds", "").trim());
        } catch (NumberFormatException e) {
            timeout = ModelSettings.DEFAULT_TIMEOUT_SECONDS;
        }
        return Optional.of(new ModelSettings(provider,
                blankToNull(props.getProperty("base.url")),
                blankToNull(props.getProperty("api.key.ref")),
                models, timeout));
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
