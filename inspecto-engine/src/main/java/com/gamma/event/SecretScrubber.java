package com.gamma.event;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sanitises {@link Event}s before they reach the append-only {@link EventStore}, so no operational
 * fact — captured log line or domain/audit event — ever persists a secret. This is the single
 * scrub seam: it runs inside {@link EventLog#emit(Event)} on every event, so an accidental
 * {@code log.info("token=" + t)} is redacted at the sink rather than relied upon at every call site.
 *
 * <p>Redacted: {@code password}/{@code secret}/{@code token}/{@code api_key}/{@code authorization}/
 * {@code bearer}/{@code session id}/{@code access_token}/{@code refresh_token}/{@code private_key}
 * occurrences in free text (the value after {@code =} or {@code :}), bare {@code Bearer xxx} tokens,
 * and any {@link Event#attributes()} value whose <em>key</em> names a secret (the value is redacted
 * whole, regardless of shape).
 *
 * <p><b>Hot-path safe.</b> {@link #emit} is the SLF4J capture path, so the common (clean) case must be
 * cheap and allocation-free: {@link #scrub(String)} does a single lowercase {@code contains} pre-check
 * and returns the same {@code String} reference when there is nothing to redact, and {@link #scrub(Event)}
 * rebuilds the record only when a field actually changed. Never throws.
 *
 * @since 4.4.0
 */
public final class SecretScrubber {

    private SecretScrubber() {}

    static final String REDACTED = "***redacted***";

    /** Words that gate the (more expensive) regexes — if none appear, the text cannot match. */
    private static final String[] HINTS = {
            "password", "passwd", "pwd", "secret", "token", "key", "authorization", "auth",
            "bearer", "session", "credential"
    };

    /** {@code key = value} / {@code key: value} where key names a secret; group 3 is the value to mask. */
    private static final Pattern KV = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|api[-_]?key|apikey|access[-_]?token|refresh[-_]?token"
                    + "|private[-_]?key|authorization|auth[-_]?token|session[-_]?id|token)\\b\\s*[=:]\\s*"
                    + "(\"?)([^\\s\"',;}]+)\\2");

    /** A bare {@code Bearer <token>} credential in a header-ish string. */
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");

    /** Attribute keys whose value is always a secret (redacted whole, ignoring its shape). */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|api[-_]?key|apikey|authorization"
                    + "|credential|private[-_]?key|session[-_]?id).*");

    /** Scrub {@code message} and {@code attributes}; returns the same instance when nothing changed. */
    public static Event scrub(Event e) {
        if (e == null) return null;
        try {
            String msg = scrub(e.message());
            Map<String, String> attrs = scrubAttributes(e.attributes());
            if (msg == e.message() && attrs == e.attributes()) return e;   // clean — no allocation
            return new Event(e.eventId(), e.ts(), e.level(), e.type(), e.source(),
                    e.pipeline(), e.correlationId(), msg, attrs, e.payload());
        } catch (RuntimeException ignore) {
            return e;   // a scrubber must never break the sink it guards
        }
    }

    /** Redact secret-looking values in free text; same reference when there is nothing to redact. */
    public static String scrub(String s) {
        if (s == null || s.isEmpty() || !hasHint(s)) return s;
        // Bearer first: otherwise KV would match "Authorization: Bearer" and redact only the word
        // "Bearer", leaving the real token (which BEARER could then no longer find).
        String out = BEARER.matcher(s).replaceAll("Bearer " + REDACTED);
        out = KV.matcher(out).replaceAll(mr -> mr.group(1) + "=" + mr.group(2) + REDACTED + mr.group(2));
        return out;
    }

    /** Returns the same map when nothing changed, else a copy with secret entries redacted. */
    private static Map<String, String> scrubAttributes(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) return attrs;
        Map<String, String> out = null;   // lazily allocated only on first change
        for (Map.Entry<String, String> en : attrs.entrySet()) {
            String k = en.getKey(), v = en.getValue();
            String nv = (k != null && SECRET_KEY.matcher(k).matches()) ? REDACTED : scrub(v);
            if (nv != v) {
                if (out == null) out = new LinkedHashMap<>(attrs);
                out.put(k, nv);
            }
        }
        return out == null ? attrs : out;
    }

    private static boolean hasHint(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        for (String h : HINTS) if (low.contains(h)) return true;
        return false;
    }
}
