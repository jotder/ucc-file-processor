package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The signal-ledger read view ({@code GET /signals}, job-framework §8.1 / §14), its live-push
 * counterpart ({@code GET /signals/stream}, event-signal-backbone-plan §S3) and the causation-tree view
 * ({@code GET /signals/tree}, which assembles one correlation chain into a parent→child forest). Signals persist as
 * {@code EventType.SIGNAL} events on the one ledger (§19.2); {@code GET /signals} reconstructs them and
 * filters by {@code type} (exact or {@code prefix.*} glob), the {@code since}/{@code until} epoch-milli
 * bounds, {@code severity} (a minimum floor), {@code source} (the emitter Ref, by {@code kind} or
 * {@code kind:id}), and {@code correlationId} — the time bounds, severity floor and correlationId apply
 * in-store, while the type glob and source match apply over the returned page (the store has no attribute
 * filter). Space-scoped like {@code /events} (the {@code /spaces/{id}} prefix routes to the space's event store).
 */
final class SignalRoutes implements RouteModule {

    /** SSE heartbeat cadence — a comment frame so an idle stream stays alive and disconnects surface
     *  (mirrors {@code NotificationRoutes.HEARTBEAT_SECONDS}). */
    private static final long HEARTBEAT_SECONDS = 15;

    @Override
    public void register(ApiContext api) {
        api.get("/signals", (e, m) -> signals(api, e));
        api.get("/signals/tree", (e, m) -> signalTree(api, e));
        api.get("/signals/stream", (e, m) -> stream(api, e));
    }

    /** {@code GET /signals?type=&since=&until=&severity=&source=&correlationId=&limit=} — the correlation-chain-filterable ledger view. */
    private Object signals(ApiContext api, HttpExchange e) {
        int limit = ApiContext.parseIntOr(ApiContext.query(e, "limit"), 200);
        Long since = parseEpochMs(ApiContext.query(e, "since"), "since");
        Long until = parseEpochMs(ApiContext.query(e, "until"), "until");
        Severity minSeverity = parseSeverity(ApiContext.query(e, "severity"));
        String source = ApiContext.query(e, "source");
        List<Signal> found = Signals.query(api.service().events(),
                ApiContext.query(e, "type"), since, until, minSeverity,
                ApiContext.query(e, "correlationId"), limit);
        return found.stream()
                .filter(s -> Signals.matchesSource(s, source))   // source has no in-store filter — apply over the page
                .map(Signal::toMap).toList();
    }

    /**
     * {@code GET /signals/tree?correlationId=&limit=} — the same ledger, assembled server-side into the
     * causation forest for one correlation chain (§8.1 causation model). Returns a bare array of root nodes;
     * each node is the full {@code /signals} signal view plus a {@code children} array of the signals it
     * caused ({@code causationId == the node's signalId}), recursively. Roots and children read oldest-first
     * (the run start is the root, the facts it caused nest beneath). {@code correlationId} is required — a
     * tree is scoped to one chain; without an anchor the ledger is an unbounded forest.
     */
    private Object signalTree(ApiContext api, HttpExchange e) {
        String correlationId = ApiContext.query(e, "correlationId");
        if (correlationId == null || correlationId.isBlank())
            throw new ApiException(400, "'correlationId' is required for /signals/tree (a tree is scoped to one correlation chain)");
        int limit = ApiContext.parseIntOr(ApiContext.query(e, "limit"), 200);
        List<Signal> found = Signals.query(api.service().events(),
                null, null, null, null, correlationId, limit);
        return Signals.assembleTree(found).stream().map(SignalRoutes::toNode).toList();
    }

    /** Project a {@link Signals.SignalNode} to the nested JSON view: the flat signal map plus a recursive
     *  {@code children} array ({@link Signal#toMap()} returns a fresh mutable map, so putting into it is safe). */
    private static Map<String, Object> toNode(Signals.SignalNode n) {
        Map<String, Object> m = n.signal().toMap();
        m.put("children", n.children().stream().map(SignalRoutes::toNode).toList());
        return m;
    }

    private static Long parseEpochMs(String s, String field) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            throw new ApiException(400, "invalid '" + field + "' (expected epoch millis): " + s);
        }
    }

    /** Absent ⇒ no severity floor; an unrecognised value is a 400 (not a silent fallback, unlike Severity.parse). */
    private static Severity parseSeverity(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Severity.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, "invalid 'severity' (expected TRACE|DEBUG|INFO|WARN|ERROR|CRITICAL): " + s);
        }
    }

    /**
     * {@code GET /signals/stream?type=&severity=&source=&correlationId=} — Server-Sent Events, live-filtered at
     * open time (no {@code since}/{@code until}/{@code limit}/historical replay in this slice; a client
     * wanting both does an initial {@code GET /signals} fetch, then opens this stream for what's next).
     * Subscribes to the space's {@link EventLog}, keeps only {@code EventType.SIGNAL} events, reconstructs
     * a {@link Signal} and applies the same type-glob/severity-floor/source/correlationId predicate {@code GET
     * /signals} uses ({@link Signals#matches}), and frames each match as a plain unnamed {@code data:}
     * frame — mirrors {@code NotificationRoutes.stream}'s SSE plumbing exactly (register-before-headers,
     * heartbeat poll, {@code finally}-deregister).
     */
    private static Object stream(ApiContext api, HttpExchange ex) throws IOException {
        EventLog log = api.service().eventLog();
        String type = ApiContext.query(ex, "type");
        Severity minSeverity = parseSeverity(ApiContext.query(ex, "severity"));
        String source = ApiContext.query(ex, "source");
        String correlationId = ApiContext.query(ex, "correlationId");

        BlockingQueue<Signal> queue = new LinkedBlockingQueue<>();
        Consumer<Event> listener = e -> {
            if (e.type() != EventType.SIGNAL) return;
            Signal s = Signal.fromEvent(e);
            if (Signals.matches(s, type, minSeverity, source, correlationId)) queue.offer(s);
        };
        // Register before the response is committed so no signal can slip through between the client
        // seeing the headers and the listener being live.
        log.addSubscriber(listener);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);         // 0 ⇒ chunked; stream an arbitrary number of bytes
        try (OutputStream os = ex.getResponseBody()) {
            writeFrame(os, ": connected\n\n"); // initial comment confirms the stream is live
            while (true) {
                Signal s = queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                writeFrame(os, s != null
                        ? "data: " + ApiContext.JSON.writeValueAsString(s.toMap()) + "\n\n"
                        : ": ping\n\n");        // heartbeat keeps the connection warm + surfaces a disconnect
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); // service shutting down — end the stream
        } catch (IOException disconnect) {
            // client went away — normal SSE termination
        } finally {
            log.removeSubscriber(listener);
        }
        return ApiContext.HANDLED;
    }

    private static void writeFrame(OutputStream os, String frame) throws IOException {
        os.write(frame.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
