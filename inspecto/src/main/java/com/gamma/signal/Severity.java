package com.gamma.signal;

import com.gamma.event.EventLevel;

/**
 * A {@link Signal}'s severity (job-framework §8.1; the six-level ladder from {@code openapi-v1.json}).
 * Distinct from {@link EventLevel} (the event ledger's log ladder) but mapped onto it when a Signal
 * persists as an Event, so signals filter by {@code minLevel} alongside everything else. {@code CRITICAL}
 * has no separate {@link EventLevel} counterpart and maps onto {@code ERROR} (lossy in that one
 * direction only — the existing precedent for this ladder).
 */
public enum Severity {
    TRACE, DEBUG, INFO, WARN, ERROR, CRITICAL;

    /** The event-ledger level this severity persists as. */
    public EventLevel toEventLevel() {
        return switch (this) {
            case TRACE -> EventLevel.TRACE;
            case DEBUG -> EventLevel.DEBUG;
            case INFO  -> EventLevel.INFO;
            case WARN  -> EventLevel.WARN;
            case ERROR, CRITICAL -> EventLevel.ERROR;
        };
    }

    /**
     * Lenient parse (case-insensitive); accepts both the wire spec's lowercase form
     * ({@code trace|debug|info|warn|error|critical}) and the legacy three-level {@code Severity}'s
     * names still present in previously-persisted events ({@code WARNING}→{@link #WARN}); unrecognised
     * / {@code null} falls back to {@link #INFO}.
     */
    public static Severity parse(String s) {
        if (s == null) return INFO;
        String u = s.trim().toUpperCase(java.util.Locale.ROOT);
        if (u.equals("WARNING")) return WARN;   // legacy 3-level Severity persisted this name
        try {
            return Severity.valueOf(u);
        } catch (IllegalArgumentException e) {
            return INFO;
        }
    }
}
