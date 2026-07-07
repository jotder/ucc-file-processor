package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shapes a handler result into the v1 response envelope {@code {data, metadata, links,
 * diagnostics}} — or, for a non-2xx status, into the structured v1 error object
 * {@code {error: {errorCode, message, recoverable, correlationId, details?}}}. Applied only when
 * the request arrived under {@code /api/v1} (dispatch sets {@link ApiContext#ATTR_V1}); legacy
 * (unversioned) responses stay byte-for-byte unchanged. {@code permissions} carries the authenticated
 * {@link Subject}'s resolved capability grants (W6) and is present only when the security module
 * attached one (Standard edition, authenticated request); absent on Personal / anonymous requests.
 * SEC-7(b): when the route declared a per-resource applicable set
 * ({@link ApiContext#resourcePermissions}), the array is refined to {@code grants ∩ applicable} —
 * the per-resource ∩ resource-state derivation design §8 describes; routes that declare nothing keep
 * the session-wide array. Design of record: docs/superpower/resource-permissions-design.md. Contract:
 * docs/superpower/api-contract-design.md §4–5, §8.
 */
final class Envelope {
    private Envelope() {}

    static Object shape(HttpExchange ex, int status, Object body) {
        return status < 400 ? success(ex, body) : error(ex, status, body);
    }

    private static Object success(HttpExchange ex, Object body) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp", Instant.now().toString());
        if (ex.getAttribute(ApiContext.ATTR_START_NANOS) instanceof Long nanos)
            metadata.put("durationMs", (System.nanoTime() - nanos) / 1_000_000);
        metadata.put("apiVersion", "v1");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", body);
        envelope.put("metadata", metadata);
        if (ex.getAttribute(ApiContext.ATTR_SELF_PATH) instanceof String self)
            envelope.put("links", Map.of("self", self));
        ApiContext.subject(ex).ifPresent(s -> envelope.put("permissions", permissions(ex, s)));
        envelope.put("diagnostics", Map.of("correlationId", String.valueOf(ApiContext.correlationId(ex))));
        return envelope;
    }

    /** SEC-7(b): the Subject's session grants, narrowed to the route-declared per-resource applicable
     *  set when one is present (sorted for a stable wire order). */
    private static java.util.List<String> permissions(HttpExchange ex, Subject s) {
        if (ex.getAttribute(ApiContext.ATTR_RESOURCE_PERMISSIONS) instanceof java.util.Set<?> applicable)
            return s.capabilities().stream().filter(applicable::contains).sorted().toList();
        return java.util.List.copyOf(s.capabilities());
    }

    /** Lift a legacy error body ({@code {"error": msg}}, possibly with extra keys such as the 422
     *  findings payload) into the v1 error object; the extra keys are preserved under {@code details}. */
    private static Object error(HttpExchange ex, int status, Object body) {
        String message = null;
        Map<String, Object> details = null;
        if (body instanceof Map<?, ?> m) {
            Object msg = m.get("error");
            if (msg != null) message = String.valueOf(msg);
            Map<String, Object> rest = new LinkedHashMap<>();
            m.forEach((k, v) -> { if (!"error".equals(k)) rest.put(String.valueOf(k), v); });
            if (!rest.isEmpty()) details = rest;
        }
        if (message == null) message = String.valueOf(body);

        Object code = ex.getAttribute(ApiContext.ATTR_ERROR_CODE);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorCode", code instanceof String c ? c : ErrorCodes.defaultFor(status));
        error.put("message", message);
        error.put("recoverable", status != 500);
        error.put("correlationId", ApiContext.correlationId(ex));
        if (details != null) error.put("details", details);
        return Map.of("error", error);
    }
}
