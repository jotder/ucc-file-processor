package com.gamma.signal;

import com.gamma.event.Event;
import com.gamma.event.EventQuery;
import com.gamma.event.EventStore;
import com.gamma.event.EventType;

import java.util.ArrayList;
import java.util.List;

/**
 * Read side of the signal ledger (job-framework §8.1, R6): query the shared {@link EventStore} for
 * {@link EventType#SIGNAL} events and reconstruct {@link Signal}s. The dotted signal {@code type} lives
 * in an attribute (the store has no attribute filter), so an optional type / {@code prefix.*} glob is
 * applied in Java over the store-filtered page; {@code correlationId}, the {@code since}/{@code until}
 * time bounds and the {@code minSeverity} floor (mapped onto the event level ladder) all filter in-store.
 */
public final class Signals {

    private Signals() {}

    /**
     * Signals matching the filters, newest first. {@code type} may be an exact type or a {@code prefix.*}
     * glob; {@code sinceMs}/{@code untilMs} are inclusive epoch-milli bounds; {@code minSeverity} keeps only
     * signals at or above that severity (via {@link Severity#toEventLevel()}); any filter may be {@code null}.
     */
    public static List<Signal> query(EventStore store, String type, Long sinceMs, Long untilMs,
                                     Severity minSeverity, String correlationId, int limit) {
        EventQuery.Builder qb = EventQuery.builder()
                .type(EventType.SIGNAL)
                .correlationId(blankToNull(correlationId))
                .from(sinceMs)
                .to(untilMs)
                .limit(limit);
        if (minSeverity != null) qb.minLevel(minSeverity.toEventLevel());
        EventQuery q = qb.build();
        List<Signal> out = new ArrayList<>();
        for (Event e : store.query(q)) {
            Signal s = Signal.fromEvent(e);
            if (matchesType(s.type(), type)) out.add(s);
        }
        return out;
    }

    /** Exact match, or a {@code prefix.*} glob, or no filter (null/blank type). */
    public static boolean matchesType(String signalType, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String f = filter.trim();
        if (f.endsWith(".*")) return signalType != null && signalType.startsWith(f.substring(0, f.length() - 1));
        return f.equalsIgnoreCase(signalType);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
