package com.gamma.control;

import com.gamma.event.AuditAttrs;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.sun.net.httpserver.HttpExchange;

/**
 * The security audit trail's capture point — turns a state-changing Control API request into one
 * append-only audit {@link Event} ({@code type = }{@link EventType#AUDIT}). Called once from
 * {@link ControlApi#dispatch} after a request resolves, so it covers every current and future
 * mutating route from a single seam (no per-handler wiring) and the engine core stays
 * identity-agnostic — actor/IP/User-Agent are read only here, at the HTTP edge.
 *
 * <h3>What is audited</h3>
 * <ul>
 *   <li>Successful {@code POST}/{@code PUT}/{@code DELETE} that {@link #classify classify}s as a real
 *       mutation (create/update/delete/trigger/…); diagnostic POSTs ({@code /test}, {@code /preview},
 *       {@code /dry-run}, {@code /validate}, {@code /assist/*}) are skipped as non-mutating.</li>
 *   <li>{@code GET .../export} — data-export actions (Category B).</li>
 *   <li>{@link #accessDenied} — a non-GET request to a forbidden/unknown route (404) or a disallowed
 *       method on a read-only route (405): the auth-free analogue of a 401/403 attempt.</li>
 * </ul>
 * Auth-gated events (login/MFA/password, true 401/403) are out of scope until the security module lands.
 *
 * @since 4.4.0
 */
final class AuditTrail {

    private AuditTrail() {}

    /** A classified action: its dotted name and coarse category, or {@code null} when not auditable. */
    record Action(String name, String category) {}

    /**
     * Record an audit event for a resolved request, if it is auditable. Never throws — auditing must
     * not disturb the response (the underlying {@link EventLog#emit} already swallows sink failures).
     *
     * @param path the route path with the {@code /api} and {@code /spaces/{id}} prefixes already stripped
     */
    static void record(HttpExchange ex, String method, String path, int status) {
        try {
            Action action = classify(method, path);
            if (action == null) return;
            String actor = ApiContext.actor(ex);
            String targetType = resource(path);
            String targetId = targetId(path);
            EventLog.current().emit(Event.builder(EventType.AUDIT)
                    .source("audit")
                    .message(actor + " " + action.name() + (targetId == null ? "" : " " + targetId))
                    .actor(actor).actorType("user")
                    .action(action.name()).actionCategory(action.category())
                    .target(targetType, targetId)
                    .ip(ApiContext.ip(ex)).userAgent(ApiContext.userAgent(ex))
                    .attr(AuditAttrs.HTTP_METHOD, method)
                    .attr(AuditAttrs.HTTP_PATH, path)
                    .attr(AuditAttrs.HTTP_STATUS, status));
        } catch (RuntimeException ignore) {
            // best effort — the audit trail must never break the request
        }
    }

    /** Record a forbidden/unknown-route attempt (404/405) for a non-GET request. Never throws. */
    static void accessDenied(HttpExchange ex, String method, String path, int status) {
        try {
            String actor = ApiContext.actor(ex);
            EventLog.current().emit(Event.builder(EventType.ACCESS_DENIED)
                    .source("audit")
                    .message(actor + " access.denied " + method + " " + path + " (" + status + ")")
                    .actor(actor).actorType("user")
                    .action("access.denied").actionCategory("authorization")
                    .ip(ApiContext.ip(ex)).userAgent(ApiContext.userAgent(ex))
                    .attr(AuditAttrs.HTTP_METHOD, method)
                    .attr(AuditAttrs.HTTP_PATH, path)
                    .attr(AuditAttrs.HTTP_STATUS, status));
        } catch (RuntimeException ignore) {
            // best effort
        }
    }

    /**
     * Map a resolved request to an auditable {@link Action}, or {@code null} when it carries no audit
     * value. Pure (no I/O) so it is unit-testable. The {@code path} has prefixes stripped.
     */
    static Action classify(String method, String path) {
        if (path == null || path.isEmpty()) return null;
        // Export actions are GET but auditable (Category B); nothing else GET is.
        if ("GET".equals(method)) {
            return path.endsWith("/export") ? new Action(resource(path) + ".exported", "export") : null;
        }
        if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) return null;
        // Non-mutating POSTs: diagnostics / previews / assist chat, and the user's own notification-feed
        // housekeeping (read/delete) — not part of the security audit trail.
        if (path.endsWith("/test") || path.endsWith("/preview") || path.endsWith("/dry-run")
                || path.equals("/validate") || path.startsWith("/assist")
                || path.startsWith("/notifications")) return null;

        String last = lastSegment(path);
        String category = "DELETE".equals(method) ? "destructive"
                : (path.startsWith("/config") || last.equals("write")) ? "configuration"
                : "data_mutation";
        String verb = switch (method) {
            case "DELETE" -> "deleted";
            case "PUT" -> "updated";
            default -> switch (last) {     // POST
                case "trigger" -> "triggered";
                case "pause" -> "paused";
                case "resume" -> "resumed";
                case "reprocess" -> "reprocessed";
                case "ack" -> "acknowledged";
                case "resolve" -> "resolved";
                case "transition" -> "transitioned";
                case "import" -> "imported";
                case "write" -> "written";
                case "evaluate" -> "evaluated";
                case "delete" -> "deleted";       // e.g. /events/views/{name}/delete
                default -> "created";
            };
        };
        return new Action(resource(path) + "." + verb, category);
    }

    /** First path segment, singularised: {@code /pipelines/x} → {@code pipeline}; {@code /} → {@code service}. */
    private static String resource(String path) {
        String[] seg = path.split("/");
        String head = seg.length > 1 && !seg[1].isEmpty() ? seg[1] : "service";
        return head.endsWith("s") ? head.substring(0, head.length() - 1) : head;
    }

    /** The {@code {id}} after the resource (second segment), or {@code null} when the path is a collection. */
    private static String targetId(String path) {
        String[] seg = path.split("/");
        return seg.length > 2 && !seg[2].isEmpty() ? seg[2] : null;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
