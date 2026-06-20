package com.gamma.control;

import com.gamma.service.SourceService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * The shared HTTP plumbing a {@link RouteModule} needs to register and serve routes without
 * depending on the {@link ControlApi} host directly. ControlApi is the sole implementation; the
 * indirection lets cohesive route groups live in their own classes (lower coupling, thinner host).
 */
interface ApiContext {

    void get(String pattern, Handler h);

    void post(String pattern, Handler h);

    void put(String pattern, Handler h);

    void delete(String pattern, Handler h);

    /** Parse the request body as a JSON object map (an empty map when the body is empty). */
    Map<String, Object> body(HttpExchange ex) throws IOException;

    /** The running service host the routes act on. */
    SourceService service();

    /** The configured write root, or {@code null} when filesystem writes are disabled. */
    Path writeRoot();

    /** Null-safe, blank-as-null string field from a parsed body map. */
    static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
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
