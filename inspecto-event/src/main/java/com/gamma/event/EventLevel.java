package com.gamma.event;

import java.util.Locale;

/**
 * Severity of an {@link Event}, ordered from least to most severe. Mirrors the SLF4J/logback level
 * ladder so the {@code EventStoreAppender} can map a captured log event 1:1, and so an
 * {@link EventQuery} can filter by a minimum severity ({@code level >= minLevel}).
 *
 * <h3>The "everything except DEBUG" rule</h3>
 * Phase-1 capture records {@code INFO} and above. {@link #CAPTURE_THRESHOLD} is the boundary:
 * {@link #isCaptured()} is {@code true} for {@code INFO}/{@code WARN}/{@code ERROR} and {@code false}
 * for {@code TRACE}/{@code DEBUG}. The enum still models the lower levels so an explicit emitter may
 * record a {@code DEBUG} event when it deliberately wants to (the threshold only gates <em>automatic</em>
 * log capture, never an explicit {@link EventLog#emit}).
 *
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public enum EventLevel {
    TRACE, DEBUG, INFO, WARN, ERROR;

    /** Lowest severity captured by automatic log bridging — {@code INFO} (DEBUG/TRACE are dropped). */
    public static final EventLevel CAPTURE_THRESHOLD = INFO;

    /** {@code true} when this level is at or above {@link #CAPTURE_THRESHOLD} (i.e. not DEBUG/TRACE). */
    public boolean isCaptured() {
        return this.ordinal() >= CAPTURE_THRESHOLD.ordinal();
    }

    /** {@code true} when this level is at least as severe as {@code other}. */
    public boolean atLeast(EventLevel other) {
        return other == null || this.ordinal() >= other.ordinal();
    }

    /**
     * Parse a level name leniently (case-insensitive; {@code WARNING}→{@link #WARN},
     * {@code ERR}/{@code SEVERE}→{@link #ERROR}, {@code FINE}/{@code FINER}/{@code FINEST}→{@link #DEBUG}).
     * Unrecognised input (including {@code null}/blank) falls back to {@link #INFO}.
     */
    public static EventLevel parse(String s) {
        if (s == null || s.isBlank()) return INFO;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE", "FINEST" -> TRACE;
            case "DEBUG", "FINE", "FINER" -> DEBUG;
            case "INFO", "CONFIG" -> INFO;
            case "WARN", "WARNING" -> WARN;
            case "ERROR", "ERR", "SEVERE", "FATAL" -> ERROR;
            default -> INFO;
        };
    }
}
