package com.gamma.notify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single {@code appUser}'s notification preference grid — which {@link NotificationCategory} is
 * delivered over which channel ({@link #IN_APP} / {@link #EMAIL} / any SPI channel id). In the auth-free
 * core there is one global preference set (per-user preferences arrive with the auth module).
 *
 * <p>{@link NotificationCategory#critical() Critical} categories bypass opt-out: {@link #enabled} always
 * returns {@code true} for them and {@link #set} refuses to change them, so a security alert can never be
 * silenced. Defaults: in-app on for available categories, email off; critical on for both (locked).
 *
 * <p>In-memory, like the other lean-default stores; durable persistence (TOON/file) is a follow-on.
 *
 * @since 4.4.0
 */
public final class NotificationPreferences {

    /** Intrinsic in-app channel (the bell feed) — always present in the core. */
    public static final String IN_APP = "inApp";
    /** External email channel — delivered only when an edition supplies a {@link NotificationChannel}. */
    public static final String EMAIL = "email";

    /** Channels shown as columns in the grid (stable order). */
    private static final List<String> CHANNELS = List.of(IN_APP, EMAIL);

    /** category id → (channel id → enabled). Critical categories are not stored here (always-on). */
    private final Map<String, Map<String, Boolean>> byCategory = new ConcurrentHashMap<>();

    public NotificationPreferences() {
        for (NotificationCategory c : NotificationCategory.values()) {
            if (c.critical()) continue;   // critical is always-on, never stored/editable
            Map<String, Boolean> ch = new ConcurrentHashMap<>();
            ch.put(IN_APP, c.available());   // default in-app on for categories that actually fire
            ch.put(EMAIL, false);            // email off by default (and no core channel to deliver it)
            byCategory.put(c.id(), ch);
        }
    }

    /** Whether {@code category} should be delivered over {@code channel}. Critical categories always true. */
    public boolean enabled(String category, String channel) {
        if (NotificationCategory.byId(category).map(NotificationCategory::critical).orElse(false)) return true;
        Map<String, Boolean> ch = byCategory.get(category);
        return ch != null && Boolean.TRUE.equals(ch.get(channel));
    }

    /** Update one category's channel toggles. No-op for unknown or {@link NotificationCategory#critical()} ones. */
    public void set(String category, Map<String, Boolean> channels) {
        NotificationCategory cat = NotificationCategory.byId(category).orElse(null);
        if (cat == null || cat.critical() || channels == null) return;
        Map<String, Boolean> ch = byCategory.get(category);
        for (String c : CHANNELS) {
            Object v = channels.get(c);
            if (v instanceof Boolean b) ch.put(c, b);
        }
    }

    /** The full grid as JSON-ready rows — one per category, with label/critical/available metadata. */
    public List<Map<String, Object>> grid() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (NotificationCategory c : NotificationCategory.values()) {
            Map<String, Boolean> channels = new LinkedHashMap<>();
            for (String ch : CHANNELS) channels.put(ch, enabled(c.id(), ch));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c.id());
            row.put("label", c.label());
            row.put("critical", c.critical());
            row.put("available", c.available());
            row.put("channels", channels);
            rows.add(row);
        }
        return rows;
    }
}
