package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Registry of known {@link PipelineNodeType}s: the {@link BuiltinNodeType built-ins} plus any contributed
 * via {@link ServiceLoader} ({@code META-INF/services/com.gamma.pipeline.PipelineNodeType}). A provider may
 * override a built-in by declaring the same {@link PipelineNodeType#type()}, so an edition can specialise a
 * node type without forking the core.
 *
 * <p>The registry is built once at class-load and is immutable thereafter.
 */
@PublicApi(since = "4.3.0")
public final class PipelineNodeTypes {

    private static final Map<String, PipelineNodeType> REGISTRY = load();

    private PipelineNodeTypes() {}

    private static Map<String, PipelineNodeType> load() {
        Map<String, PipelineNodeType> m = new LinkedHashMap<>();
        for (BuiltinNodeType b : BuiltinNodeType.values()) m.put(b.type(), b);
        // Providers are layered last so an edition can override a built-in of the same type().
        for (PipelineNodeType t : ServiceLoader.load(PipelineNodeType.class)) m.put(t.type(), t);
        return Map.copyOf(m);
    }

    /** The descriptor for {@code type}, if registered. */
    public static Optional<PipelineNodeType> get(String type) {
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
     * the source for the UI palette: each carries its {@link PipelineNodeType#category() category},
     * {@link PipelineNodeType#label() label}, {@link PipelineNodeType#description() description} and the
     * relationships it {@link PipelineNodeType#emits() emits}/{@link PipelineNodeType#accepts() accepts}.
     */
    public static Collection<PipelineNodeType> catalog() {
        return REGISTRY.values();
    }

    /** The {@link NodeCategory} of {@code type}, if registered. */
    public static Optional<NodeCategory> categoryOf(String type) {
        return get(type).map(PipelineNodeType::category);
    }

    /** Whether {@code type} is a registered node type in the given {@link NodeCategory} (e.g. any sink). */
    public static boolean isCategory(String type, NodeCategory category) {
        PipelineNodeType t = REGISTRY.get(type);
        return t != null && t.category() == category;
    }
}
