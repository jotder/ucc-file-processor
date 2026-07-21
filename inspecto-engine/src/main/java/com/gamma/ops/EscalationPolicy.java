package com.gamma.ops;

import com.gamma.config.io.ConfigCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * What to do to an {@link ObjectType#INCIDENT} when it breaches its SLA (INC-4) — applied by
 * {@link ObjectService#sweepIncidentSla} after the breach is stamped. Config-driven ({@code *_escalation.toon},
 * an {@code escalation { … }} block), so the workflow semantics live in per-deployment config rather than
 * hardcoded. When no policy is loaded the sweep behaves exactly as before (breach event only).
 *
 * <p>Any subset of the three actions may be set:
 * <ul>
 *   <li>{@code severity} — the new severity to bump the incident to (e.g. {@code critical}); null = leave it.</li>
 *   <li>{@code reassignQueue} — a {@link com.gamma.ops.queue.Queue} id to re-route the incident into (its
 *       router picks the member); null = leave the assignee.</li>
 *   <li>{@code renotify} — emit an {@code OBJECT_ESCALATED} event (default true) so the notify chain re-alerts.</li>
 * </ul>
 *
 * @since 4.9.0
 */
@com.gamma.api.PublicApi(since = "4.9.0")
public record EscalationPolicy(String severity, String reassignQueue, boolean renotify) {

    /** Whether this policy would change anything on the incident itself (vs. only re-notifying). */
    public boolean mutates() {
        return (severity != null && !severity.isBlank()) || (reassignQueue != null && !reassignQueue.isBlank());
    }

    /** Load an {@code *_escalation.toon} (an {@code escalation { … }} block). */
    @SuppressWarnings("unchecked")
    public static EscalationPolicy load(Path path) throws IOException {
        Map<String, Object> root = ConfigCodec.toMap(Files.readString(path));
        Object esc = root.get("escalation");
        if (!(esc instanceof Map)) throw new IllegalArgumentException(path + " has no 'escalation' block");
        return fromMap((Map<String, Object>) esc);
    }

    /** Parse from a decoded {@code escalation { … }} map. {@code renotify} defaults to true. */
    public static EscalationPolicy fromMap(Map<String, Object> e) {
        if (e == null) throw new IllegalArgumentException("missing 'escalation' block");
        Object rn = e.getOrDefault("renotify", e.get("re_notify"));
        boolean renotify = rn == null || Boolean.parseBoolean(String.valueOf(rn));
        return new EscalationPolicy(str(e.getOrDefault("severity", e.get("escalate_severity"))),
                str(e.getOrDefault("reassign_queue", e.get("queue"))), renotify);
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
