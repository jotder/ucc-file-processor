package com.gamma.control;

import com.gamma.notify.ChannelConfig;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationRule;
import com.gamma.notify.NotificationService;
import com.gamma.notify.NotificationStore;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * In-app notification feed routes ({@code /notifications*}, Phase B2): the bell-icon feed, the unread
 * badge count, and the read/read-all/delete state actions. The feed is mutable (unlike the append-only
 * {@code /events*}), so these are ordinary state transitions on the {@link NotificationStore}. The
 * real-time push counterpart ({@code GET /notifications/stream}, SSE) is registered separately (B3).
 */
final class NotificationRoutes implements RouteModule {

    /** SSE heartbeat cadence — a comment frame so an idle stream stays alive and disconnects surface. */
    private static final long HEARTBEAT_SECONDS = 15;

    @Override
    public void register(ApiContext api) {
        api.get("/notifications", (e, m) -> feed(api, e));
        api.get("/notifications/stream", (e, m) -> stream(api, e));
        api.get("/notifications/unread-count", (e, m) -> Map.of("count", store(api).unreadCount()));
        api.post("/notifications/read-all", (e, m) -> Map.of("updated", store(api).markAllRead()));
        api.post("/notifications/([^/]+)/read", (e, m) -> store(api).markRead(ApiContext.name(m))
                .map(Notification::toMap)
                .orElseThrow(() -> new ApiException(404, "no notification '" + ApiContext.name(m) + "'")));
        api.get("/notifications/preferences", (e, m) -> api.service().notificationPreferences().grid());
        api.put("/notifications/preferences", (e, m) -> savePreferences(api, api.body(e)));
        // Channel destinations admin CRUD (registered before the /notifications/{id} routes below, which
        // only match a single segment — "channels/{id}" is two, so there's no collision either way).
        api.get("/notifications/channels", (e, m) -> listChannels(api));
        api.post("/notifications/channels", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> createChannel(api, api.body(e))));
        api.put("/notifications/channels/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> updateChannel(api, ApiContext.name(m), api.body(e))));
        api.delete("/notifications/channels/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> deleteChannel(api, ApiContext.name(m))));
        // Authored notification rules admin CRUD (same shape as channels above; registered before the
        // /notifications/{id} routes below for the same reason — "rules/{id}" is two segments).
        api.get("/notifications/rules", (e, m) -> listRules(api));
        api.post("/notifications/rules", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> createRule(api, api.body(e))));
        api.put("/notifications/rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> updateRule(api, ApiContext.name(m), api.body(e))));
        api.delete("/notifications/rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> deleteRule(api, ApiContext.name(m))));
        api.delete("/notifications/([^/]+)", (e, m) -> {
            if (!store(api).archive(ApiContext.name(m)))
                throw new ApiException(404, "no notification '" + ApiContext.name(m) + "'");
            return Map.of("id", ApiContext.name(m), "deleted", true);
        });
    }

    /**
     * {@code PUT /notifications/preferences} — apply the edited grid. Body: {@code {"preferences":[{category,
     * channels:{inApp,email}}, …]}}. Critical/unknown categories are ignored by the store (locked); the full
     * refreshed grid is returned.
     */
    @SuppressWarnings("unchecked")
    private static Object savePreferences(ApiContext api, Map<String, Object> body) {
        var prefs = api.service().notificationPreferences();
        Object rows = body.get("preferences");
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> r)) continue;
                Object category = r.get("category");
                if (!(r.get("channels") instanceof Map<?, ?> channels) || category == null) continue;
                Map<String, Boolean> toggles = new java.util.LinkedHashMap<>();
                channels.forEach((k, v) -> { if (v instanceof Boolean b) toggles.put(String.valueOf(k), b); });
                prefs.set(String.valueOf(category), toggles);
            }
        }
        return prefs.grid();
    }

    private static NotificationStore store(ApiContext api) {
        return api.service().notifications();
    }

    // ── channel destinations (admin CRUD; C4 — persisted as `channel` components per space) ─────────

    private static final String CHANNEL_TYPE = "channel";

    /** {@code GET /notifications/channels} — the saved channel destinations (empty when no write root). */
    private static Object listChannels(ApiContext api) {
        Path root = api.writeRoot();
        if (root == null) return List.of();
        return new ComponentStore(root.resolve("registry")).list(CHANNEL_TYPE).stream()
                .map(ComponentRegistry.Component::content).toList();
    }

    /** {@code POST /notifications/channels} — create; 422 missing fields, 409 duplicate id. */
    private static Object createChannel(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = channelStore(api);
        ChannelConfig ch = parse(body, System.currentTimeMillis());
        if (exists(store, ch.id()))
            throw new ApiException(409, "channel '" + ch.id() + "' already exists (use PUT to update)");
        return write(store, ch.id(), ch.toMap());
    }

    /** {@code PUT /notifications/channels/{id}} — replace; 404 unknown. The id + createdAt are immutable. */
    private static Object updateChannel(ApiContext api, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = channelStore(api);
        Map<String, Object> existing = existing(store, id);
        long createdAt = existing.get("createdAt") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        Map<String, Object> patched = new LinkedHashMap<>(existing);
        patched.putAll(body);
        patched.put("id", id);                 // storage key is bound from the path, never a stale body id
        patched.put("createdAt", createdAt);   // preserve the original creation stamp
        return write(store, id, parse(patched, createdAt).toMap());
    }

    /** {@code DELETE /notifications/channels/{id}} — 404 unknown, else {@code {deleted:id}}. */
    private static Object deleteChannel(ApiContext api, String id) throws IOException {
        ComponentStore store = channelStore(api);
        existing(store, id);
        store.delete(CHANNEL_TYPE, id);
        return Map.of("deleted", id);
    }

    private static ComponentStore channelStore(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "notification channel write").resolve("registry"));
    }

    private static ChannelConfig parse(Map<String, Object> body, long defaultCreatedAt) {
        try {
            return ChannelConfig.fromMap(body, defaultCreatedAt);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    /** {@code store.exists}, mapping an unsafe id (e.g. containing {@code ..}) to 422, not a generic 500. */
    private static boolean exists(ComponentStore store, String id) {
        try {
            return store.exists(CHANNEL_TYPE, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static Map<String, Object> existing(ComponentStore store, String id) {
        try {
            return store.get(CHANNEL_TYPE, id).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, "channel '" + id + "' not found"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static Map<String, Object> write(ComponentStore store, String id, Map<String, Object> content)
            throws IOException {
        try {
            return store.write(CHANNEL_TYPE, id, content).content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    // ── authored notification rules (admin CRUD; persisted as `notification-rule` components per space) ──

    private static final String RULE_TYPE = "notification-rule";

    /** {@code GET /notifications/rules} — the operator-authored rules (empty when no write root). */
    private static Object listRules(ApiContext api) {
        Path root = api.writeRoot();
        if (root == null) return List.of();
        return new ComponentStore(root.resolve("registry")).list(RULE_TYPE).stream()
                .map(ComponentRegistry.Component::content).toList();
    }

    /** {@code POST /notifications/rules} — create; 422 missing fields, 409 duplicate id. */
    private static Object createRule(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = ruleStore(api);
        NotificationRule rule = parseRule(body);
        if (existsRule(store, rule.id()))
            throw new ApiException(409, "rule '" + rule.id() + "' already exists (use PUT to update)");
        return writeRule(store, rule.id(), rule.toMap());
    }

    /** {@code PUT /notifications/rules/{id}} — replace; 404 unknown. The id is immutable (bound from the path). */
    private static Object updateRule(ApiContext api, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = ruleStore(api);
        existingRule(store, id);
        Map<String, Object> patched = new LinkedHashMap<>(body);
        patched.put("id", id);   // storage key is bound from the path, never a stale body id
        return writeRule(store, id, parseRule(patched).toMap());
    }

    /** {@code DELETE /notifications/rules/{id}} — 404 unknown, else {@code {deleted:id}}. */
    private static Object deleteRule(ApiContext api, String id) throws IOException {
        ComponentStore store = ruleStore(api);
        existingRule(store, id);
        store.delete(RULE_TYPE, id);
        return Map.of("deleted", id);
    }

    private static ComponentStore ruleStore(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "notification rule write").resolve("registry"));
    }

    private static NotificationRule parseRule(Map<String, Object> body) {
        try {
            return NotificationRule.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static boolean existsRule(ComponentStore store, String id) {
        try {
            return store.exists(RULE_TYPE, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static Map<String, Object> existingRule(ComponentStore store, String id) {
        try {
            return store.get(RULE_TYPE, id).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, "rule '" + id + "' not found"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static Map<String, Object> writeRule(ComponentStore store, String id, Map<String, Object> content)
            throws IOException {
        try {
            return store.write(RULE_TYPE, id, content).content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    /** {@code GET /notifications?limit=} — the active feed, newest-first. */
    private static List<Map<String, Object>> feed(ApiContext api, HttpExchange ex) {
        int limit = ApiContext.parseIntOr(ApiContext.query(ex, "limit"), 50);
        return store(api).recent(limit).stream().map(Notification::toMap).toList();
    }

    /**
     * {@code GET /notifications/stream} — Server-Sent Events for real-time delivery. Holds the exchange
     * open and pushes each new notification as a {@code data:} frame, so the bell badge updates without a
     * page reload. The framework-free JDK {@code HttpServer} has no async continuation, so the handler
     * <b>blocks</b> for the connection's lifetime — fine here because the server runs on a virtual-thread
     * executor (a parked virtual thread is cheap) and the auth-free core has a single {@code appUser}.
     * The UI falls back to polling the feed/unread-count if the stream drops.
     */
    private static Object stream(ApiContext api, HttpExchange ex) throws IOException {
        NotificationService svc = api.service().notificationService();
        BlockingQueue<Notification> queue = new LinkedBlockingQueue<>();
        Consumer<Notification> listener = queue::offer;
        Thread me = Thread.currentThread();
        Runnable closer = me::interrupt;        // svc.close() runs this to unblock the poll on shutdown
        // Register before the response is committed so no notification can slip through between the client
        // seeing the headers and the listener being live.
        svc.addListener(listener);
        svc.onClose(closer);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);         // 0 ⇒ chunked; stream an arbitrary number of bytes
        try (OutputStream os = ex.getResponseBody()) {
            writeFrame(os, ": connected\n\n");  // initial comment confirms the stream is live
            while (true) {
                Notification n = queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                writeFrame(os, n != null
                        ? "data: " + ApiContext.JSON.writeValueAsString(n.toMap()) + "\n\n"
                        : ": ping\n\n");        // heartbeat keeps the connection warm + surfaces a disconnect
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); // service shutting down — end the stream
        } catch (IOException disconnect) {
            // client went away — normal SSE termination
        } finally {
            svc.removeListener(listener);
            svc.removeOnClose(closer);
        }
        return ApiContext.HANDLED;
    }

    private static void writeFrame(OutputStream os, String frame) throws IOException {
        os.write(frame.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
