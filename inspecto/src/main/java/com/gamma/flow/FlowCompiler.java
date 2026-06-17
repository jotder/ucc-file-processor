package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Compile-back: recovers, from a lifted {@link FlowGraph}, the execution inputs the existing engine
 * consumes — the inverse of {@link PipelineLift}. Because the lift is lossless (it carries the typed
 * {@code PipelineConfig} sub-records / schema maps / {@code SchemaSelector} verbatim as node config
 * values), {@code compile} simply groups the nodes back by role; every original input is recoverable
 * unchanged. A round-trip ({@code lift → compile}) that returns the same inputs is the <b>Phase-1
 * parity gate</b> (the IR loses nothing).
 *
 * <p><b>Scope (Phase 1):</b> this recovers the inputs; it does not yet <em>invoke</em>
 * {@code SourceProcessor} from them — driving the engine from a {@code FlowGraph} (so the existing
 * suite literally runs through the lifted path) needs the branch-aware executor scheduled for Phase 3
 * (doc §13 R3 / §14 T12). Until then the lossless round-trip below is the gate.
 */
@PublicApi(since = "4.3.0")
public final class FlowCompiler {

    private FlowCompiler() {}

    /**
     * The execution inputs recovered from a {@link FlowGraph}, grouped by role.
     *
     * @param name        the pipeline id ({@link FlowGraph#name()})
     * @param active      the poll gate ({@link FlowGraph#active()})
     * @param acquisition the single entry {@code acquisition} node (the engine's {@code source:} + {@code dirs.poll})
     * @param parser      the single {@code parser} node (csv/grammar/fixedwidth + schema(s)/selector/segments)
     * @param dedups      the dedup nodes ({@code marker} and/or {@code fingerprint}), in chain order
     * @param sinks       every {@code sink} node (per-schema outputs + any quarantine)
     * @param gap         the optional {@code gap} reporting node
     */
    public record Compiled(String name, boolean active,
                           Optional<FlowNode> acquisition, Optional<FlowNode> parser,
                           List<FlowNode> dedups, List<FlowNode> sinks, Optional<FlowNode> gap) {}

    /** Recover the engine inputs from {@code g} by grouping its nodes by role. */
    public static Compiled compile(FlowGraph g) {
        FlowNode acq = null, parser = null, gap = null;
        List<FlowNode> dedups = new ArrayList<>();
        List<FlowNode> sinks = new ArrayList<>();
        for (FlowNode n : g.nodes()) {
            String t = n.type();
            if (BuiltinNodeType.ACQUISITION.type().equals(t)) acq = n;
            else if (BuiltinNodeType.PARSER.type().equals(t)) parser = n;
            else if (BuiltinNodeType.GAP.type().equals(t)) gap = n;
            else if (FlowNodeTypes.isCategory(t, NodeCategory.SINK)) sinks.add(n);   // any sink subtype
            else if (BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(t)
                    || BuiltinNodeType.TRANSFORM_DEDUP_FINGERPRINT.type().equals(t)) dedups.add(n);
        }
        return new Compiled(g.name(), g.active(),
                Optional.ofNullable(acq), Optional.ofNullable(parser),
                List.copyOf(dedups), List.copyOf(sinks), Optional.ofNullable(gap));
    }
}
