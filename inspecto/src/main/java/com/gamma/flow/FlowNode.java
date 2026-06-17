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
 * <p>{@code name} and {@code description} are <b>given by the user</b> (authored {@code *_flow.toon})
 * and may name a business object or concept — e.g. a {@code sink.view} named after the entity a
 * downstream KPI consumes. They are display/documentation metadata for the UI (doc §3.1) and do not
 * affect execution; a legacy-lifted node may carry derived defaults or none.
 *
 * <p>Instances are immutable; {@code config} is a defensive, unmodifiable copy.
 *
 * @param id          node id, unique within its graph
 * @param type        node type discriminator (e.g. {@code acquisition}, {@code transform.filter}, {@code sink.view})
 * @param name        user-given display name (may name a business object/concept); {@code null} if unset
 * @param description user-given one-line description; {@code null} if unset
 * @param config      node-local configuration (immutable copy; never {@code null} — empty if none)
 * @param use         registry reference {@code <type>/<name>}, or {@code null} for none
 */
@PublicApi(since = "4.3.0")
public record FlowNode(String id, String type, String name, String description,
                       Map<String, Object> config, String use) {

    public FlowNode {
        Objects.requireNonNull(id, "node.id");
        Objects.requireNonNull(type, "node.type");
        config = (config == null) ? Map.of() : Map.copyOf(config);
    }

    /** A node with config + optional registry ref, but no display name/description (e.g. a lifted internal node). */
    public FlowNode(String id, String type, Map<String, Object> config, String use) {
        this(id, type, null, null, config, use);
    }

    /** A node with config but no registry reference. */
    public static FlowNode of(String id, String type, Map<String, Object> config) {
        return new FlowNode(id, type, null, null, config, null);
    }

    /** A bare node — no config, no registry reference. */
    public static FlowNode of(String id, String type) {
        return new FlowNode(id, type, null, null, Map.of(), null);
    }

    /** Whether this node references a reusable registry component. */
    public boolean hasUse() {
        return use != null && !use.isBlank();
    }

    /** Whether the user gave this node a display name (authored flows set it; lifted nodes may not). */
    public boolean hasName() {
        return name != null && !name.isBlank();
    }

    /** A config value by key, or {@code null} if absent. */
    public Object cfg(String key) {
        return config.get(key);
    }
}
