package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers "what references this component?" over a set of {@link PipelineGraph}s by scanning {@code use:}
 * references (doc §4.1 / §14 T8). This drives <b>safe-delete</b>: deleting a registry component that some
 * flow still references is refused — generalising the existing {@code connectionInUse} 409 guard from
 * connections to every component type ({@code grammar}/{@code schema}/{@code transform}/{@code sink}/…).
 */
@PublicApi(since = "4.3.0")
public final class PipelineReferences {

    private PipelineReferences() {}

    /** The {@code use:} references in a graph: node id → component ref ({@code type/name}), for nodes that declare one. */
    public static Map<String, String> uses(PipelineGraph g) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PipelineNode n : g.nodes()) {
            if (n.hasUse()) out.put(n.id(), n.use().trim());
        }
        return out;
    }

    /** The distinct component refs a graph depends on. */
    public static Set<String> referencedComponents(PipelineGraph g) {
        return new LinkedHashSet<>(uses(g).values());
    }

    /** Names of the graphs (in iteration order) that reference {@code componentRef} ({@code type/name}). */
    public static List<String> referencedBy(String componentRef, Collection<PipelineGraph> graphs) {
        if (componentRef == null || componentRef.isBlank()) return List.of();
        String ref = componentRef.trim();
        List<String> out = new ArrayList<>();
        for (PipelineGraph g : graphs) {
            if (uses(g).containsValue(ref)) out.add(g.name());
        }
        return out;
    }

    /** Whether any graph references {@code componentRef} — the safe-delete guard ({@code true} ⇒ refuse delete). */
    public static boolean isReferenced(String componentRef, Collection<PipelineGraph> graphs) {
        return !referencedBy(componentRef, graphs).isEmpty();
    }
}
