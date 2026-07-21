package com.gamma.notify;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One in-app notification in the user's feed — the mutable counterpart to an append-only
 * {@link com.gamma.event.Event}. A notification is rendered from a {@link NotificationRule} when a
 * triggering event occurs, then lives through a {@link NotificationState} lifecycle (unread → read →
 * archived). In the auth-free core the recipient is always {@code appUser}.
 *
 * @param id         opaque unique id (UUID)
 * @param ts         creation time, epoch millis (UTC)
 * @param category   user-facing grouping (e.g. {@code pipeline}, {@code job}, {@code ops}) — also the
 *                   preference key that gates delivery
 * @param sourceType the {@link com.gamma.event.EventType} that triggered it (provenance/diagnostics)
 * @param sourceId   the triggering event's correlation/event id, or {@code null}
 * @param title      short rendered headline
 * @param body       rendered detail line
 * @param state      lifecycle state
 * @param dedupeKey  key collapsing repeat notifications of the same kind (rate-limit / digest seam)
 * @param readAt     epoch millis the user read it, or {@code null} while unread
 * @since 4.4.0
 */
public record Notification(String id, long ts, String category, String sourceType, String sourceId,
                           String title, String body, NotificationState state, String dedupeKey,
                           Long readAt) {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Fills sensible defaults (id, ts, state). */
    public Notification {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (ts == 0) ts = System.currentTimeMillis();
        if (state == null) state = NotificationState.UNREAD;
        if (category == null || category.isBlank()) category = "general";
        if (title == null) title = "";
        if (body == null) body = "";
    }

    /** A fresh UNREAD notification (id/ts auto-filled). */
    public static Notification create(String category, String sourceType, String sourceId,
                                      String title, String body, String dedupeKey) {
        return new Notification(null, 0L, category, sourceType, sourceId, title, body,
                NotificationState.UNREAD, dedupeKey, null);
    }

    /** This notification marked {@link NotificationState#READ} at {@code at} (idempotent if already read). */
    public Notification asRead(long at) {
        return state == NotificationState.READ ? this
                : new Notification(id, ts, category, sourceType, sourceId, title, body,
                        NotificationState.READ, dedupeKey, at);
    }

    /** This notification moved to {@link NotificationState#ARCHIVED}. */
    public Notification asArchived() {
        return new Notification(id, ts, category, sourceType, sourceId, title, body,
                NotificationState.ARCHIVED, dedupeKey, readAt);
    }

    /** This notification with {@code body} replaced — used to render a per-channel {@code ChannelConfig}
     *  template in place of the rule's default body for one delivery, without mutating the stored/in-app
     *  copy (event-signal-backbone-plan §4.5.1). */
    Notification withBody(String body) {
        return new Notification(id, ts, category, sourceType, sourceId, title, body, state, dedupeKey, readAt);
    }

    /** The event time as an ISO-8601 UTC string. */
    public String timestamp() {
        return ISO.format(Instant.ofEpochMilli(ts));
    }

    /** JSON-ready view (stable key order) — backs the {@code /notifications*} API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ts", ts);
        m.put("timestamp", timestamp());
        m.put("category", category);
        m.put("sourceType", sourceType);
        m.put("sourceId", sourceId);
        m.put("title", title);
        m.put("body", body);
        m.put("state", state.name());
        m.put("readAt", readAt);
        return m;
    }
}
