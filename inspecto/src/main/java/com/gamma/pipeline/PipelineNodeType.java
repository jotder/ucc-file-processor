package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.Set;

/**
 * The plugin seam for flow node types. Mirrors
 * {@link com.gamma.acquire.CollectorConnectorFactory}: the engine {@link java.util.ServiceLoader}s the
 * available node types and matches one by its {@link #type()} discriminator. The lean core ships the
 * {@link BuiltinNodeType built-ins}; editions/plugins contribute extra types by listing a provider in
 * {@code META-INF/services/com.gamma.pipeline.PipelineNodeType}.
 *
 * <p>Phase-1 scope is <b>descriptor-level</b> — {@link #type()} + {@link #category()} + a UI
 * {@link #label()}/{@link #description()}, plus the relationships a node {@link #emits()}/
 * {@link #accepts()}. These feed the lift, the (Phase-3) wiring validator <b>and the UI palette</b>
 * (the built-in processor definitions are not deferred — the visualiser needs them, doc §6). Execution
 * and dry-run hooks are added in later phases, so this interface stays small and stable for now.
 */
@PublicApi(since = "4.3.0")
public interface PipelineNodeType {

    /** The {@code type} value this descriptor handles, e.g. {@code "acquisition"}. */
    String type();

    /** The {@link NodeCategory family} this node type belongs to (palette grouping + role checks). */
    default NodeCategory category() {
        return NodeCategory.TRANSFORM;
    }

    /** Short human label for the UI palette / node inspector (defaults to {@link #type()}). */
    default String label() {
        return type();
    }

    /** One-line description for the UI palette / node inspector (defaults to empty). */
    default String description() {
        return "";
    }

    /**
     * Control/split relationships this node type may emit, besides operator-defined named
     * {@code route:*} branches (see {@link #emitsNamedRoutes()}). Enforced by
     * {@link PipelineValidator} (T9): an outbound edge whose relationship is not emitted here is rejected.
     */
    default Set<String> emits() {
        return Set.of(PipelineRel.DATA);
    }

    /**
     * Relationships this node type accepts inbound. An entry node accepts nothing.
     * {@link PipelineValidator} enforces this on {@code data} edges (a {@code data} edge's target must
     * accept {@code data}); a control/split outcome routed to a handler is governed by the emitter.
     */
    default Set<String> accepts() {
        return Set.of(PipelineRel.DATA);
    }

    /** Whether this node type emits operator-defined {@code route:<key>} branches (a parser dispatcher, route, plugin). */
    default boolean emitsNamedRoutes() {
        return false;
    }
}
