package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Registry of known {@link FlowNodeType}s: the {@link BuiltinNodeType built-ins} plus any contributed
 * via {@link ServiceLoader} ({@code META-INF/services/com.gamma.flow.FlowNodeType}). A provider may
 * override a built-in by declaring the same {@link FlowNodeType#type()}, so an edition can specialise a
 * node type without forking the core.
 *
 * <p>The registry is built once at class-load and is immutable thereafter.
 */
@PublicApi(since = "4.3.0")
public final class FlowNodeTypes {

    private static final Map<String, FlowNodeType> REGISTRY = load();

    private FlowNodeTypes() {}

    private static Map<String, FlowNodeType> load() {
        Map<String, FlowNodeType> m = new LinkedHashMap<>();
        for (BuiltinNodeType b : BuiltinNodeType.values()) m.put(b.type(), b);
        // Providers are layered last so an edition can override a built-in of the same type().
        for (FlowNodeType t : ServiceLoader.load(FlowNodeType.class)) m.put(t.type(), t);
        return Map.copyOf(m);
    }

    /** The descriptor for {@code type}, if registered. */
    public static Optional<FlowNodeType> get(String type) {
        return Optional.ofNullable(REGISTRY.get(type));
    }

    /** Whether {@code type} is a registered node type. */
    public static boolean isKnown(String type) {
        return REGISTRY.containsKey(type);
    }

    /** All registered node-type discriminators (built-ins + providers), in registration order. */
    public static Set<String> all() {
        return REGISTRY.keySet();
    }

    /**
     * All registered node-type <em>descriptors</em> (built-ins + providers), in registration order —
     * the source for the UI palette: each carries its {@link FlowNodeType#category() category},
     * {@link FlowNodeType#label() label}, {@link FlowNodeType#description() description} and the
     * relationships it {@link FlowNodeType#emits() emits}/{@link FlowNodeType#accepts() accepts}.
     */
    public static Collection<FlowNodeType> catalog() {
        return REGISTRY.values();
    }

    /** The {@link NodeCategory} of {@code type}, if registered. */
    public static Optional<NodeCategory> categoryOf(String type) {
        return get(type).map(FlowNodeType::category);
    }

    /** Whether {@code type} is a registered node type in the given {@link NodeCategory} (e.g. any sink). */
    public static boolean isCategory(String type, NodeCategory category) {
        FlowNodeType t = REGISTRY.get(type);
        return t != null && t.category() == category;
    }
}
