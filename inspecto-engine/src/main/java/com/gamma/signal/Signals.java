package com.gamma.signal;

import com.gamma.event.Event;
import com.gamma.event.EventQuery;
import com.gamma.event.EventStore;
import com.gamma.event.EventType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * The shared type/severity-floor/source/correlationId predicate used both by the {@link #query} page
     * filter (source applied by the route over the returned page — the store has no attribute filter) and
     * by a live subscriber (e.g. {@code /signals/stream}, S3) applying the same filters to freshly-emitted
     * signals. {@code type} is an exact match or a {@code prefix.*} glob (see {@link #matchesType});
     * {@code minSeverity} keeps only signals at or above that floor (natural enum order,
     * {@code TRACE < ... < CRITICAL}); {@code source} matches the emitter Ref (see {@link #matchesSource});
     * any filter may be {@code null}/blank to mean "no filter".
     */
    public static boolean matches(Signal s, String type, Severity minSeverity, String source, String correlationId) {
        if (!matchesType(s.type(), type)) return false;
        if (minSeverity != null && s.severity().ordinal() < minSeverity.ordinal()) return false;
        if (!matchesSource(s, source)) return false;
        if (correlationId != null && !correlationId.isBlank() && !correlationId.equals(s.correlationId())) return false;
        return true;
    }

    /**
     * Whether {@code s}'s {@code source} {@link Ref} matches {@code filter} — a case-insensitive exact match
     * against either the source's {@code kind} (e.g. {@code job}, {@code pipeline}) or its compact
     * {@code kind:id} form (e.g. {@code job:nightly_recon}). A {@code null}/blank filter means "no filter";
     * a signal whose {@code source} is {@code null} is excluded once a filter is given.
     */
    public static boolean matchesSource(Signal s, String filter) {
        if (filter == null || filter.isBlank()) return true;
        Ref src = s.source();
        if (src == null) return false;
        String f = filter.trim();
        return f.equalsIgnoreCase(src.kind()) || f.equalsIgnoreCase(compact(src));
    }

    /** The compact {@code kind:id} form of a Ref (or just {@code id}/{@code kind} when the other is absent). */
    private static String compact(Ref r) {
        if (r.kind() == null) return r.id();
        return r.id() == null ? r.kind() : r.kind() + ":" + r.id();
    }

    /**
     * A node in the causation tree: a {@link Signal} plus the signals it directly caused (its
     * {@code children} carry {@code causationId == this.signalId}). A forest of these is what
     * {@link #assembleTree} returns.
     */
    public record SignalNode(Signal signal, List<SignalNode> children) {}

    /**
     * Assemble a flat list of signals into a causation forest. A signal is a child of the signal its
     * {@link Signal#causationId()} names; a signal whose causation names nothing in the set (a {@code null}
     * causation, or a parent outside this window/correlation) becomes a root, so nothing is ever dropped.
     * Roots and each node's children are ordered oldest-first (a chain reads top-down: the run start is the
     * root, the facts it caused nest beneath). Input order is irrelevant. Causation is a DAG by construction
     * (a signal's cause always already exists), but a self-cause or an injected cycle is defensively broken —
     * such a node is surfaced as a root rather than looping — so the result is always a finite forest.
     */
    public static List<SignalNode> assembleTree(List<Signal> signals) {
        List<Signal> ordered = new ArrayList<>(signals);
        ordered.sort(Comparator.comparingLong((Signal s) -> s.at().toEpochMilli())
                .thenComparing(Signal::signalId));

        Map<String, Signal> byId = new LinkedHashMap<>();
        Map<String, SignalNode> nodes = new LinkedHashMap<>();
        for (Signal s : ordered) {
            byId.put(s.signalId(), s);
            nodes.put(s.signalId(), new SignalNode(s, new ArrayList<>()));
        }

        List<SignalNode> roots = new ArrayList<>();
        for (Signal s : ordered) {
            SignalNode node = nodes.get(s.signalId());
            SignalNode parent = s.causationId() == null ? null : nodes.get(s.causationId());
            if (parent != null && parent != node && !formsCycle(byId, s)) parent.children().add(node);
            else roots.add(node);   // no in-set parent, self-cause, or a would-be cycle → a root
        }
        return roots;
    }

    /** Walk the causation chain up from {@code start}; a revisited id means the {@code causationId}
     *  pointers form a cycle (impossible for real data, defensive against injected ledgers). */
    private static boolean formsCycle(Map<String, Signal> byId, Signal start) {
        Set<String> seen = new HashSet<>();
        for (Signal s = start; s != null; s = byId.get(s.causationId())) {
            if (!seen.add(s.signalId())) return true;
            if (s.causationId() == null) return false;
        }
        return false;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
