package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.service.SourceService;
import com.gamma.service.SpaceManager;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * The shared HTTP plumbing a {@link RouteModule} needs to register and serve routes without
 * depending on the {@link ControlApi} host directly. ControlApi is the sole implementation; the
 * indirection lets cohesive route groups live in their own classes (lower coupling, thinner host).
 */
interface ApiContext {

    /** Returned by a handler that has already written its own (non-JSON) response. */
    Object HANDLED = new Object();

    /** Shared response serialiser (default mapper; same output as the dispatcher's JSON writer). */
    ObjectMapper JSON = new ObjectMapper();

    // ── per-exchange attributes set by ControlApi.dispatch (v1 transport spine, v4.8.0) ─────────
    /** Marks a request that arrived under {@code /api/v1} — responses get the {@link Envelope}. */
    String ATTR_V1             = "inspecto.v1";
    /** The request's correlation id (caller-supplied {@code Correlation-ID} header, or issued). */
    String ATTR_CORRELATION_ID = "inspecto.correlationId";
    /** {@code System.nanoTime()} at dispatch start (v1 {@code metadata.durationMs}). */
    String ATTR_START_NANOS    = "inspecto.startNanos";
    /** The original request path incl. the {@code /api/v1} prefix (v1 {@code links.self}). */
    String ATTR_SELF_PATH      = "inspecto.selfPath";
    /** A specific {@link ErrorCodes} value chosen by the throwing site (else derived from status). */
    String ATTR_ERROR_CODE     = "inspecto.errorCode";
    /** The active {@link Idempotency.Store} for this exchange (present only for a keyed write). */
    String ATTR_IDEMPOTENCY_STORE = "inspecto.idempotency.store";
    /** The idempotency cache key for this exchange (present only for a keyed write). */
    String ATTR_IDEMPOTENCY_KEY   = "inspecto.idempotency.key";

    /** JSON bodies at or above this size are gzipped when the client sent {@code Accept-Encoding: gzip}. */
    int GZIP_MIN_BYTES = 1024;

    /** True when this request arrived under the {@code /api/v1} prefix (v1 envelope semantics). */
    static boolean v1(HttpExchange ex) {
        return Boolean.TRUE.equals(ex.getAttribute(ATTR_V1));
    }

    /** The request's correlation id (set by dispatch on every request), or {@code null} pre-dispatch. */
    static String correlationId(HttpExchange ex) {
        Object v = ex.getAttribute(ATTR_CORRELATION_ID);
        return v == null ? null : v.toString();
    }

    void get(String pattern, Handler h);

    void post(String pattern, Handler h);

    void put(String pattern, Handler h);

    void delete(String pattern, Handler h);

    /** Parse the request body as a JSON object map (an empty map when the body is empty). */
    Map<String, Object> body(HttpExchange ex) throws IOException;

    /** The running service host the routes act on (the request's bound space, per the {@code /spaces/{id}} seam). */
    SourceService service();

    /** The container of all hosted spaces — for the server-global {@code SpaceRoutes} CRUD group only. */
    SpaceManager spaces();

    /** The configured write root, or {@code null} when filesystem writes are disabled. */
    Path writeRoot();

    /** The bound space's data directory (where partition stores live), or {@code null} if unavailable.
     *  Used by query execution to resolve a dataset's {@code physicalRef} to its at-rest Parquet (W4). */
    Path dataRoot();

    /** The acting identity for the audit trail. Auth-free core has no session, so the actor defaults to
     *  {@code appUser}; an edition's security module supplies the real principal via {@code X-Actor}. */
    static String actor(HttpExchange ex) {
        String a = ex.getRequestHeaders().getFirst("X-Actor");
        return (a == null || a.isBlank()) ? "appUser" : a.trim();
    }

    /** The originating client IP — the first {@code X-Forwarded-For} hop when present (proxy/dev), else
     *  the socket peer address. {@code null} only if the address is somehow unavailable. */
    static String ip(HttpExchange ex) {
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        var addr = ex.getRemoteAddress();
        return addr == null || addr.getAddress() == null ? null : addr.getAddress().getHostAddress();
    }

    /** The request {@code User-Agent}, or {@code null} if absent. */
    static String userAgent(HttpExchange ex) {
        return ex.getRequestHeaders().getFirst("User-Agent");
    }

    /** Decode the {@code key} query-string parameter, or {@code null} if absent. */
    static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key))
                return URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Null-safe, blank-as-null string field from a parsed body map. */
    static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
    }

    /** Extract the {@code sampleRows} array from a request body (each element a row map); empty if absent. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> sampleRows(Map<String, Object> body) {
        List<Map<String, Object>> sample = new ArrayList<>();
        if (body.get("sampleRows") instanceof List<?> rows) {
            for (Object o : rows) if (o instanceof Map<?, ?> r) sample.add((Map<String, Object>) r);
        }
        return sample;
    }

    /** Parse {@code s} as an int, or {@code def} when blank/non-numeric. */
    static int parseIntOr(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Write {@code body} as JSON with an explicit status (e.g. a 422 with a findings payload); returns
     *  {@link #HANDLED}. A {@code /api/v1} request gets the {@link Envelope} shaping (legacy bodies are
     *  byte-for-byte unchanged); bodies ≥ {@link #GZIP_MIN_BYTES} are gzipped when the client accepts it. */
    static Object respondJson(HttpExchange ex, int status, Object body) throws IOException {
        Object payload = v1(ex) ? Envelope.shape(ex, status, body) : body;
        byte[] bytes = JSON.writeValueAsBytes(payload);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        Idempotency.capture(ex, status, bytes);   // cache the pre-compression body for a keyed-write replay (W5)
        bytes = maybeGzip(ex, bytes);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        return HANDLED;
    }

    /** Gzip {@code bytes} (setting {@code Content-Encoding}) when large enough and the client
     *  negotiated it via {@code Accept-Encoding: gzip}; otherwise return them untouched. */
    private static byte[] maybeGzip(HttpExchange ex, byte[] bytes) throws IOException {
        if (bytes.length < GZIP_MIN_BYTES) return bytes;
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (accept == null || !accept.toLowerCase(java.util.Locale.ROOT).contains("gzip")) return bytes;
        var out = new java.io.ByteArrayOutputStream(Math.max(64, bytes.length / 4));
        try (var gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        ex.getResponseHeaders().set("Content-Encoding", "gzip");
        return out.toByteArray();
    }

    /** Write {@code text} with an explicit {@code Content-Type} (e.g. {@code text/csv}); returns {@link #HANDLED}. */
    static Object respondText(HttpExchange ex, String text, String contentType) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        return HANDLED;
    }

    /** Decode the first captured path segment (the {@code id} in {@code /things/{id}}). */
    static String name(Matcher m) {
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    /** Decode the {@code g}-th captured path segment (e.g. group 2 = the {@code id} in {@code /{type}/{id}}). */
    static String param(Matcher m, int g) {
        return URLDecoder.decode(m.group(g), StandardCharsets.UTF_8);
    }
}
