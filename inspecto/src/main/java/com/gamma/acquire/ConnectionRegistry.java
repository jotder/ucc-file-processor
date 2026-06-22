package com.gamma.acquire;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.MDC;

import com.gamma.event.EventLog;

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
 * <p><b>Per-space isolation.</b> The one process hosts many isolated spaces, so profiles are namespaced by the
 * calling thread's {@link EventLog#SPACE_MDC_KEY space} MDC (same routing as the per-space {@code EventLog} and
 * {@code MetricRegistry} label). The write path ({@code SourceService.registerConnection}) sets the MDC to the
 * service's own space; the read path ({@code forConfig} on the poll thread) inherits the space the poll cycle
 * runs under. With no space MDC set — single-space and every existing test — everything resolves to the
 * {@value #DEFAULT_SPACE} namespace, identical to the flat registry it replaces.
 *
 * <p>Secrets are never materialised here — a {@link ConnectionProfile} holds credential <em>references</em>
 * ({@code ${ENV:…}}), resolved by {@link SecretResolver} only at connect time inside the connector.
 */
public final class ConnectionRegistry {

    private ConnectionRegistry() {}

    /** Namespace used when the thread carries no {@link EventLog#SPACE_MDC_KEY space} MDC. */
    static final String DEFAULT_SPACE = "default";

    /** {@code space -> (id -> profile)}; the outer map is keyed by the resolved space namespace. */
    private static final Map<String, Map<String, ConnectionProfile>> PROFILES = new ConcurrentHashMap<>();

    private static String space() {
        String s = MDC.get(EventLog.SPACE_MDC_KEY);
        return (s == null || s.isEmpty()) ? DEFAULT_SPACE : s;
    }

    private static Map<String, ConnectionProfile> spaceMap() {
        return PROFILES.computeIfAbsent(space(), k -> new ConcurrentHashMap<>());
    }

    /** Publish (or replace) a profile in the current space, keyed by {@link ConnectionProfile#id()}; {@code null} is ignored. */
    public static void register(ConnectionProfile profile) {
        if (profile != null) spaceMap().put(profile.id(), profile);
    }

    /** The profile bound to {@code id} in the current space, if one has been registered. */
    public static Optional<ConnectionProfile> find(String id) {
        if (id == null) return Optional.empty();
        Map<String, ConnectionProfile> m = PROFILES.get(space());
        return Optional.ofNullable(m == null ? null : m.get(id.trim()));
    }

    /** Drop a single profile from the current space (tests / reconfiguration). */
    public static void remove(String id) {
        if (id == null) return;
        Map<String, ConnectionProfile> m = PROFILES.get(space());
        if (m != null) m.remove(id.trim());
    }

    /** Clear the registry across all spaces (tests). */
    public static void clear() {
        PROFILES.clear();
    }
}
