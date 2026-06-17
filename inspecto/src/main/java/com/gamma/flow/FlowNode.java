package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.Map;
import java.util.Objects;

/**
 * One typed node in a {@link FlowGraph}. A node is a thin declaration: its {@code type} names
 * the processor (see {@link BuiltinNodeType}), {@code config} holds the node-local settings, and
 * {@code use} optionally references a reusable registry component ({@code grammar/pipe-delimited},
 * {@code connection/sftp-prod}, …) resolved at load time.
 *
 * <p>Instances are immutable; {@code config} is a defensive, unmodifiable copy.
 *
 * @param id     node id, unique within its graph
 * @param type   node type discriminator (e.g. {@code acquisition}, {@code transform.filter})
 * @param config node-local configuration (immutable copy; never {@code null} — empty if none)
 * @param use    registry reference {@code <type>/<name>}, or {@code null} for none
 */
@PublicApi(since = "4.3.0")
public record FlowNode(String id, String type, Map<String, Object> config, String use) {

    public FlowNode {
        Objects.requireNonNull(id, "node.id");
        Objects.requireNonNull(type, "node.type");
        config = (config == null) ? Map.of() : Map.copyOf(config);
    }

    /** A node with config but no registry reference. */
    public static FlowNode of(String id, String type, Map<String, Object> config) {
        return new FlowNode(id, type, config, null);
    }

    /** A bare node — no config, no registry reference. */
    public static FlowNode of(String id, String type) {
        return new FlowNode(id, type, Map.of(), null);
    }

    /** Whether this node references a reusable registry component. */
    public boolean hasUse() {
        return use != null && !use.isBlank();
    }

    /** A config value by key, or {@code null} if absent. */
    public Object cfg(String key) {
        return config.get(key);
    }
}
