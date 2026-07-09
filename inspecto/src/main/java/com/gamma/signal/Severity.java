package com.gamma.signal;

import com.gamma.event.EventLevel;

/**
 * A {@link Signal}'s severity (job-framework §8.1). Distinct from {@link EventLevel} (the event
 * ledger's log ladder) but mapped onto it when a Signal persists as an Event, so signals filter by
 * {@code minLevel} alongside everything else.
 */
public enum Severity {
    INFO, WARNING, CRITICAL;

    /** The event-ledger level this severity persists as. */
    public EventLevel toEventLevel() {
        return switch (this) {
            case INFO     -> EventLevel.INFO;
            case WARNING  -> EventLevel.WARN;
            case CRITICAL -> EventLevel.ERROR;
        };
    }

    /** Lenient parse (case-insensitive); unrecognised / null falls back to {@link #INFO}. */
    public static Severity parse(String s) {
        if (s == null) return INFO;
        try {
            return Severity.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INFO;
        }
    }
}
