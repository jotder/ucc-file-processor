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

    /** Write {@code body} as JSON with an explicit status (e.g. a 422 with a findings payload); returns {@link #HANDLED}. */
    static Object respondJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        return HANDLED;
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
