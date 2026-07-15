package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.service.CollectorService;
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
    /** The authenticated {@link Subject} (W6), set by {@link ControlApi#dispatch} once an
     *  {@link Authenticator} validates the request; absent on Personal edition (no Authenticator present)
     *  and on the public bootstrap/health surface. */
    String ATTR_SUBJECT           = "inspecto.subject";
    /** SEC-7(b): the capability set applicable to the single resource this response carries, declared by
     *  the route via {@link #resourcePermissions}; {@link Envelope} intersects it with the Subject's
     *  session grants. Absent ⇒ the envelope keeps the session-wide array (lists, un-migrated routes). */
    String ATTR_RESOURCE_PERMISSIONS = "inspecto.resourcePermissions";

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

    /** The authenticated {@link Subject}, when {@link ControlApi#dispatch} resolved one for this request
     *  (W6: Standard edition, security module present). Empty on Personal edition. */
    static java.util.Optional<Subject> subject(HttpExchange ex) {
        return ex.getAttribute(ATTR_SUBJECT) instanceof Subject s ? java.util.Optional.of(s) : java.util.Optional.empty();
    }

    /** AuthZ gate (W6): when a {@link Subject} is attached (Standard edition, authenticated request) it
     *  must carry {@code capability}, else {@code 403 PERMISSION_DENIED}. A no-op on Personal edition — no
     *  {@link Authenticator} is ever present there, so no {@link Subject} is ever attached and every route
     *  stays open, unchanged. */
    static void requireCapability(HttpExchange ex, String capability) {
        if (ex.getAttribute(ATTR_SUBJECT) instanceof Subject s && !s.capabilities().contains(capability))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "missing capability '" + capability + "'");
    }

    /** SEC-7(b): declare the capability set applicable to the single resource this response carries —
     *  design-of-record `docs/superpower/resource-permissions-design.md`. The v1 envelope then emits
     *  {@code permissions = subject grants ∩ applicable} (per-resource ∩ resource-state, §8) instead of
     *  the session-wide array. An affordance signal only — enforcement stays {@link #requireCapability}. */
    static void resourcePermissions(HttpExchange ex, java.util.Set<String> applicable) {
        ex.setAttribute(ATTR_RESOURCE_PERMISSIONS, java.util.Set.copyOf(applicable));
    }

    /** Wrap {@code h} so it first runs the {@link #requireCapability} gate for {@code capability} — the
     *  one-line opt-in a write route uses to declare "this action needs X" (W6, guideline 13: capability
     *  verbs, never roles). Route registration otherwise unchanged. */
    static Handler withCapability(String capability, Handler h) {
        return (ex, m) -> {
            requireCapability(ex, capability);
            return h.handle(ex, m);
        };
    }

    void get(String pattern, Handler h);

    void post(String pattern, Handler h);

    void put(String pattern, Handler h);

    void patch(String pattern, Handler h);

    void delete(String pattern, Handler h);

    /** Parse the request body as a JSON object map (an empty map when the body is empty). */
    Map<String, Object> body(HttpExchange ex) throws IOException;

    /** The running service host the routes act on (the request's bound space, per the {@code /spaces/{id}} seam). */
    CollectorService service();

    /** The container of all hosted spaces — for the server-global {@code SpaceRoutes} CRUD group only. */
    SpaceManager spaces();

    /** The configured write root, or {@code null} when filesystem writes are disabled. */
    Path writeRoot();

    /** The bound space's data directory (where partition stores live), or {@code null} if unavailable.
     *  Used by query execution to resolve a dataset's {@code physicalRef} to its at-rest Parquet (W4). */
    Path dataRoot();

    /** The acting identity for the audit trail. When the security module authenticated this request
     *  (W6), the resolved {@link Subject}'s id is authoritative. Otherwise (Personal edition, or a public
     *  route no {@link Authenticator} ran on) the actor is the caller-supplied {@code X-Actor} header,
     *  defaulting to {@code appUser} — the historic auth-free behaviour, unchanged. */
    static String actor(HttpExchange ex) {
        if (ex.getAttribute(ATTR_SUBJECT) instanceof Subject s) return s.id();
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

    /**
     * Optional {@code ?limit=&offset=} slice over an already-in-memory list (ui-design-review R6a —
     * the pipeline/job registries are unbounded today). An absent/blank {@code limit} returns
     * {@code all} unchanged — byte-identical to the pre-existing behavior for every caller that
     * doesn't pass it. A negative {@code offset} clamps to 0; an offset past the end yields an empty list.
     */
    static <T> List<T> paged(List<T> all, HttpExchange ex) {
        String limitParam = query(ex, "limit");
        if (limitParam == null || limitParam.isBlank()) return all;
        int limit = Math.max(0, parseIntOr(limitParam, all.size()));
        int offset = Math.max(0, parseIntOr(query(ex, "offset"), 0));
        if (offset >= all.size()) return List.of();
        return all.subList(offset, Math.min(all.size(), offset + limit));
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
