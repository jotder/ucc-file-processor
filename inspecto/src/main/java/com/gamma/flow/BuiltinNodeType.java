package com.gamma.flow;

import java.util.Set;

/**
 * The node types the lean core ships. Each value's {@link #type()} string is the canonical
 * discriminator used in {@link FlowNode#type()} and in {@code *_flow.toon}. The {@code accepts}/
 * {@code emits} sets are advisory descriptors for the lift and the (Phase-3) wiring validator;
 * operator-defined {@code route:*} branches are flagged by {@link #emitsNamedRoutes()}.
 *
 * <p>Reflects the §15 capability inventory: the {@code transform.*} family includes the
 * index-anchored {@code transform.filter} (G1) and the two <em>distinct</em> dedup subtypes (G2 —
 * marker vs fingerprint are different subsystems and must not be flattened).
 */
public enum BuiltinNodeType implements FlowNodeType {

    // ── entry / acquisition ──────────────────────────────────────────────────────
    ACQUISITION("acquisition", Set.of(), Set.of(FlowRel.DATA, FlowRel.GAP, FlowRel.FAILURE), false),
    ADAPTER("adapter", Set.of(), Set.of(FlowRel.DATA), false),

    // ── parse ────────────────────────────────────────────────────────────────────
    // A parser may be a plain reader (data) or a selector/segment dispatcher (named routes + unmatched).
    PARSER("parser", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA, FlowRel.UNMATCHED), true),

    // ── transform family (§3.4 + §15) ─────────────────────────────────────────────
    TRANSFORM_MAP("transform.map", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA), false),
    TRANSFORM_FILTER("transform.filter", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA, FlowRel.DROPPED), false),
    TRANSFORM_SELECT("transform.select", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA), false),
    TRANSFORM_DERIVE("transform.derive", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA), false),
    TRANSFORM_VALIDATE("transform.validate", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA, FlowRel.INVALID), false),
    TRANSFORM_DEDUP_MARKER("transform.dedup.marker", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA, FlowRel.DUPLICATE), false),
    TRANSFORM_DEDUP_FINGERPRINT("transform.dedup.fingerprint", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA, FlowRel.DUPLICATE), false),
    TRANSFORM_ROUTE("transform.route", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA), true),
    TRANSFORM_SPLIT("transform.split", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA), false),
    TRANSFORM_MERGE("transform.merge", Set.of(FlowRel.DATA), Set.of(FlowRel.DATA), false),

    // ── enrich / sink / reporting ─────────────────────────────────────────────────
    ENRICHMENT("enrichment", Set.of(FlowRel.DATA, FlowRel.ON_COMMIT), Set.of(FlowRel.DATA, FlowRel.ON_COMMIT), false),
    SINK("sink", Set.of(FlowRel.DATA), Set.of(FlowRel.SUCCESS, FlowRel.FAILURE, FlowRel.ON_COMMIT), false),
    ALERT("alert", Set.of(FlowRel.DATA, FlowRel.GAP, FlowRel.FAILURE), Set.of(), false),
    GAP("gap", Set.of(FlowRel.GAP), Set.of(), false),
    EVENT("event", Set.of(FlowRel.DATA, FlowRel.SUCCESS, FlowRel.FAILURE, FlowRel.GAP), Set.of(), false);

    private final String type;
    private final Set<String> accepts;
    private final Set<String> emits;
    private final boolean emitsNamedRoutes;

    BuiltinNodeType(String type, Set<String> accepts, Set<String> emits, boolean emitsNamedRoutes) {
        this.type = type;
        this.accepts = accepts;
        this.emits = emits;
        this.emitsNamedRoutes = emitsNamedRoutes;
    }

    @Override public String type() { return type; }
    @Override public Set<String> accepts() { return accepts; }
    @Override public Set<String> emits() { return emits; }
    @Override public boolean emitsNamedRoutes() { return emitsNamedRoutes; }
}
