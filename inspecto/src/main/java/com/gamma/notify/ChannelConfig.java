package com.gamma.notify;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A persisted notification-channel <em>destination</em> the operator manages through the
 * {@code /notifications/channels*} admin CRUD — {@code id}, {@code kind} (e.g. {@code EMAIL}/{@code WEBHOOK}),
 * the {@code target} deliveries go to (an address / a URL), an optional {@code description}, an
 * {@code enabled} flag and a {@code createdAt} stamp. It is <b>authored config only</b>: the live delivery
 * path still resolves channels from the {@code notify.*} JVM flags via the {@link NotificationChannel} SPI
 * ({@code ServiceLoader}); wiring these persisted destinations into dispatch is a separate follow-up.
 *
 * <p>Distinct from the {@link NotificationChannel} SPI interface (a delivery implementation) — this is the
 * managed record of where a channel points, persisted as a {@code channel} component per space.
 */
public record ChannelConfig(String id, String kind, String target, String description,
                            boolean enabled, long createdAt) {

    /**
     * Parse + validate a channel from a request/stored map. {@code id}/{@code kind}/{@code target} are
     * required (blank → {@link IllegalArgumentException} → 422); {@code enabled} defaults true; {@code
     * createdAt} is taken from the map when present (a round-tripped/updated record) else {@code
     * defaultCreatedAt} (a fresh create).
     */
    public static ChannelConfig fromMap(Map<String, Object> m, long defaultCreatedAt) {
        String id = require(m, "id");
        String kind = require(m, "kind");
        String target = require(m, "target");
        String description = str(m, "description");
        boolean enabled = !(m.get("enabled") instanceof Boolean b) || b;
        long createdAt = m.get("createdAt") instanceof Number n ? n.longValue() : defaultCreatedAt;
        return new ChannelConfig(id, kind, target, description, enabled, createdAt);
    }

    /** The wire shape the UI's {@code NotificationChannel} consumes. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("target", target);
        m.put("description", description);
        m.put("enabled", enabled);
        m.put("createdAt", createdAt);
        return m;
    }

    private static String require(Map<String, Object> m, String key) {
        String v = str(m, key);
        if (v == null) throw new IllegalArgumentException("id, kind and target are required");
        return v;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
    }
}
