package com.gamma.service;

import com.gamma.acquire.ConnectionProfile;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reusable connection profiles by id (Data Acquisition), loaded from {@code *_connection.toon}. Extracted
 * from {@code CollectorService} (M2 decomposition) as a standalone in-memory registry: no lifecycle wiring,
 * no coupling to the ingest lock, scheduler, or event bus.
 *
 * <p>This owns only the per-service map. The mirror into the <b>process-wide</b>
 * {@code com.gamma.acquire.ConnectionRegistry} (the static poll path's lookup) stays in
 * {@code CollectorService}'s register/unregister methods, because that write is scoped to the service's
 * own space via its {@code underSpace(...)} MDC wrapper.
 */
final class ConnectionProfileRegistry {

    private final Map<String, ConnectionProfile> profiles = new ConcurrentHashMap<>();

    /** Register a profile keyed by {@link ConnectionProfile#id()}; {@code null} ignored. Re-registration replaces. */
    void register(ConnectionProfile profile) {
        if (profile != null) profiles.put(profile.id(), profile);
    }

    /** All registered profiles by id (immutable snapshot). */
    Map<String, ConnectionProfile> all() {
        return Map.copyOf(profiles);
    }

    /** A registered profile by id, if any. */
    Optional<ConnectionProfile> byId(String id) {
        return Optional.ofNullable(id == null ? null : profiles.get(id.trim()));
    }

    /** Drop a profile by id; idempotent. */
    void remove(String id) {
        if (id != null) profiles.remove(id.trim());
    }
}
