package com.gamma.agent.model;

import com.gamma.agentkernel.model.ModelTier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence + secret resolution for {@link ProviderSettings} (v4.1).
 *
 * <h3>File format</h3>
 * A small properties file ({@code -Dassist.settings.file}, default
 * {@code config/assist-settings.properties} under the working directory):
 * <pre>
 *   provider=anthropic
 *   base.url=                      (optional)
 *   api.key.ref=ANTHROPIC_API_KEY  (env var / system property NAME — never the key)
 *   model.small=claude-haiku-4-5
 *   model.medium=claude-sonnet-4-6
 *   model.large=claude-opus-4-8
 *   timeout.seconds=60
 * </pre>
 *
 * <h3>Secrets</h3>
 * Raw API keys are <b>never written to disk</b>. A key submitted via {@code POST /assist/settings}
 * is kept only in the in-memory session store ({@link #setSessionKey}); across restarts the key is
 * resolved from the environment via {@link ProviderSettings#apiKeyRef()}. Resolution order:
 * session key → system property named by the ref → env var named by the ref.
 */
public final class AssistModelSettings {

    private static final String PROP_FILE = "assist.settings.file";
    private static final String DEFAULT_FILE = "config/assist-settings.properties";

    /** Raw keys submitted over the API, by provider id. Process-lifetime only; never persisted. */
    private static final Map<String, String> SESSION_KEYS = new ConcurrentHashMap<>();

    private AssistModelSettings() {}

    /** The settings file path ({@code -Dassist.settings.file}, default {@value #DEFAULT_FILE}). */
    public static Path path() {
        String p = System.getProperty(PROP_FILE);
        return Path.of(p == null || p.isBlank() ? DEFAULT_FILE : p);
    }

    /** Load persisted settings; empty when no file exists (deployment falls back to env wiring). */
    public static Optional<ProviderSettings> load() {
        Path file = path();
        if (!Files.isRegularFile(file)) return Optional.empty();
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read assist settings " + file, e);
        }
        String provider = props.getProperty("provider", "").trim();
        if (provider.isEmpty()) return Optional.empty();
        EnumMap<ModelTier, String> models = new EnumMap<>(ModelTier.class);
        for (ModelTier t : ModelTier.values()) {
            String m = props.getProperty("model." + t.name().toLowerCase());
            if (m != null && !m.isBlank()) models.put(t, m.trim());
        }
        int timeout;
        try {
            timeout = Integer.parseInt(props.getProperty("timeout.seconds", "").trim());
        } catch (NumberFormatException e) {
            timeout = ProviderSettings.DEFAULT_TIMEOUT_SECONDS;
        }
        return Optional.of(new ProviderSettings(provider,
                blankToNull(props.getProperty("base.url")),
                blankToNull(props.getProperty("api.key.ref")),
                models, timeout));
    }

    /** Persist settings (no secrets — see class doc). Creates the parent directory if needed. */
    public static void save(ProviderSettings s) {
        Properties props = new Properties();
        props.setProperty("provider", s.provider());
        if (s.baseUrl() != null) props.setProperty("base.url", s.baseUrl());
        if (s.apiKeyRef() != null) props.setProperty("api.key.ref", s.apiKeyRef());
        for (Map.Entry<ModelTier, String> e : s.models().entrySet()) {
            props.setProperty("model." + e.getKey().name().toLowerCase(), e.getValue());
        }
        props.setProperty("timeout.seconds", Integer.toString(s.timeoutSeconds()));
        Path file = path();
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Inspecto assist model settings (no secrets here — keys come from "
                        + "the env var named by api.key.ref, or the in-memory session store)");
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write assist settings " + file, e);
        }
    }

    /** Hold a raw key for this process only (submitted via the settings endpoint). */
    public static void setSessionKey(String provider, String apiKey) {
        if (provider == null) return;
        if (apiKey == null || apiKey.isBlank()) SESSION_KEYS.remove(provider);
        else SESSION_KEYS.put(provider, apiKey.trim());
    }

    /** Resolve the effective API key: session key → system property(ref) → env(ref). May be null. */
    public static String resolveApiKey(ProviderSettings s) {
        String session = SESSION_KEYS.get(s.provider());
        if (session != null) return session;
        String ref = s.apiKeyRef() != null ? s.apiKeyRef()
                : ProviderSettings.defaultApiKeyRef(s.provider());
        if (ref == null || ref.isBlank()) return null;
        String v = System.getProperty(ref);
        if (v == null || v.isBlank()) v = System.getenv(ref);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
