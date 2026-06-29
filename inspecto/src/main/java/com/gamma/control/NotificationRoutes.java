package com.gamma.control;

import com.gamma.notify.Notification;
import com.gamma.notify.NotificationService;
import com.gamma.notify.NotificationStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
