package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.Objects;

/**
 * A directed edge between two {@link PipelineNode}s, carrying a relationship ({@link PipelineRel}).
 * {@code rel} defaults to {@link PipelineRel#DATA} — the normal record-set flow; control and split
 * relationships make side-paths (failure / unmatched / gap / on_commit / dropped / …) first-class
 * instead of buried flags.
 *
 * @param from source node id
 * @param rel  relationship (never blank — a blank/{@code null} value normalises to {@code data})
 * @param to   target node id
 */
@PublicApi(since = "4.3.0")
public record PipelineEdge(String from, String rel, String to) {

    public PipelineEdge {
        Objects.requireNonNull(from, "edge.from");
        Objects.requireNonNull(to, "edge.to");
        if (rel == null || rel.isBlank()) rel = PipelineRel.DATA;
    }

    /** A default {@code data} edge from {@code from} to {@code to}. */
    public static PipelineEdge data(String from, String to) {
        return new PipelineEdge(from, PipelineRel.DATA, to);
    }

    /** Whether this is a normal {@code data} edge (vs a control / split / route edge). */
    public boolean isData() {
        return PipelineRel.DATA.equals(rel);
    }
}
