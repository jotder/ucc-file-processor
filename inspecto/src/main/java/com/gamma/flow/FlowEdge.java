package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.Objects;

/**
 * A directed edge between two {@link FlowNode}s, carrying a relationship ({@link FlowRel}).
 * {@code rel} defaults to {@link FlowRel#DATA} — the normal record-set flow; control and split
 * relationships make side-paths (failure / unmatched / gap / on_commit / dropped / …) first-class
 * instead of buried flags.
 *
 * @param from source node id
 * @param rel  relationship (never blank — a blank/{@code null} value normalises to {@code data})
 * @param to   target node id
 */
@PublicApi(since = "4.3.0")
public record FlowEdge(String from, String rel, String to) {

    public FlowEdge {
        Objects.requireNonNull(from, "edge.from");
        Objects.requireNonNull(to, "edge.to");
        if (rel == null || rel.isBlank()) rel = FlowRel.DATA;
    }

    /** A default {@code data} edge from {@code from} to {@code to}. */
    public static FlowEdge data(String from, String to) {
        return new FlowEdge(from, FlowRel.DATA, to);
    }

    /** Whether this is a normal {@code data} edge (vs a control / split / route edge). */
    public boolean isData() {
        return FlowRel.DATA.equals(rel);
    }
}
