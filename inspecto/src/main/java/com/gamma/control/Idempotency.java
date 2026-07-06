package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code Idempotency-Key} support for retryable writes (W5; guideline 29). A POST/PUT/DELETE carrying
 * an {@code Idempotency-Key} header has its first JSON response cached (per-instance, TTL + LRU
 * bounded); a retry with the same key <b>replays</b> that response without re-running the handler — so
 * a retried job trigger returns the same {@code runId} without submitting a second run, and a retried
 * create does not 409.
 *
 * <p><b>Scope:</b> covers retry-after-response (the common failure-recovery case). It does not dedupe
 * two <em>simultaneously in-flight</em> duplicates (no reservation step) — acceptable for the
 * single-process control plane; the gateway handles request-level throttling. Only responses with
 * status &lt; 500 are cached (a 5xx may be transient — a retry should genuinely re-run). The captured
 * bytes are the pre-compression JSON, so a replay is written uncompressed regardless of negotiation.
 */
final class Idempotency {

    private Idempotency() {}

    private static final long TTL_MS = 10 * 60_000L;
    private static final int CAP = 1000;

    record Entry(int status, byte[] body, long expiresAt) {}

    /** A per-{@link ControlApi}-instance bounded, TTL cache (not shared across instances → no test leakage). */
    static final class Store {
        private final Map<String, Entry> map = Collections.synchronizedMap(
                new LinkedHashMap<String, Entry>(64, 0.75f, false) {
                    // Fully-qualify the value type: inside a Map subclass the bare name 'Entry' resolves to
                    // the inherited java.util.Map.Entry, not this class's record — which would break the override.
                    @Override protected boolean removeEldestEntry(Map.Entry<String, Idempotency.Entry> eldest) {
                        return size() > CAP;
                    }
                });

        Entry get(String key) {
            Entry e = map.get(key);
            if (e == null) return null;
            if (System.currentTimeMillis() > e.expiresAt()) {
                map.remove(key);
                return null;
            }
            return e;
        }

        void put(String key, int status, byte[] body) {
            map.put(key, new Entry(status, body.clone(), System.currentTimeMillis() + TTL_MS));
        }
    }

    /** The cache key for a write, or {@code null} when idempotency does not apply (non-write / no header). */
    static String keyFor(HttpExchange ex, String method, String rawPath) {
        if (!("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) return null;
        String k = ex.getRequestHeaders().getFirst("Idempotency-Key");
        return (k == null || k.isBlank()) ? null : method + " " + rawPath + " " + k.trim();
    }

    /** Cache a just-computed JSON response (pre-compression) for replay, when this exchange carries a key. */
    static void capture(HttpExchange ex, int status, byte[] jsonBytes) {
        if (status >= 500) return;
        if (ex.getAttribute(ApiContext.ATTR_IDEMPOTENCY_STORE) instanceof Store s
                && ex.getAttribute(ApiContext.ATTR_IDEMPOTENCY_KEY) instanceof String k) {
            s.put(k, status, jsonBytes);
        }
    }

    /** Write a previously-cached response, flagged {@code Idempotency-Replayed: true}. */
    static void replay(HttpExchange ex, Entry hit) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Idempotency-Replayed", "true");
        ex.sendResponseHeaders(hit.status(), hit.body().length);
        ex.getResponseBody().write(hit.body());
    }
}
