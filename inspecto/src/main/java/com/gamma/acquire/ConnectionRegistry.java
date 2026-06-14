package com.gamma.acquire;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of {@link ConnectionProfile}s by id (Data Acquisition roadmap Phase E wiring) — the
 * same global-accessor idiom as {@link com.gamma.metrics.MetricRegistry#global()} / {@link StabilityGate#shared()}
 * / {@link AcquisitionLedgers#shared()}.
 *
 * <p><b>Why this exists.</b> Connection profiles are authored as {@code *_connection.toon} and loaded into the
 * {@code SourceService} instance registry. But the poll cycle that actually builds a connector
 * ({@link SourceConnectors#forConfig}) runs on the <em>static</em> {@code SourceProcessor} path, which has no
 * handle to the service. A remote {@link SourceConnectorFactory} needs the resolved profile (host / port /
 * credentials / base path) for the {@code source.connection: <id>} its pipeline binds to. This shared registry
 * is the bridge: the service publishes each loaded profile here, and {@code forConfig} resolves the binding by
 * id and hands the profile to the factory.
 *
 * <p>Secrets are never materialised here — a {@link ConnectionProfile} holds credential <em>references</em>
 * ({@code ${ENV:…}}), resolved by {@link SecretResolver} only at connect time inside the connector.
 */
public final class ConnectionRegistry {

    private ConnectionRegistry() {}

    private static final Map<String, ConnectionProfile> PROFILES = new ConcurrentHashMap<>();

    /** Publish (or replace) a profile, keyed by {@link ConnectionProfile#id()}; {@code null} is ignored. */
    public static void register(ConnectionProfile profile) {
        if (profile != null) PROFILES.put(profile.id(), profile);
    }

    /** The profile bound to {@code id}, if one has been registered. */
    public static Optional<ConnectionProfile> find(String id) {
        return Optional.ofNullable(id == null ? null : PROFILES.get(id.trim()));
    }

    /** Drop a single profile (tests / reconfiguration). */
    public static void remove(String id) {
        if (id != null) PROFILES.remove(id.trim());
    }

    /** Clear the registry (tests). */
    public static void clear() {
        PROFILES.clear();
    }
}
