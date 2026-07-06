package com.gamma.agent.kernel.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The id→{@link Tool} table a capability draws on. */
public interface ToolRegistry {

    /** The tool registered under {@code id}, if any. */
    Optional<Tool> get(String id);

    /** The registered tool ids. */
    Set<String> ids();

    /** An immutable registry over the given tools (keyed by {@code spec().id()}; last one wins). */
    static ToolRegistry of(Collection<Tool> tools) {
        Map<String, Tool> byId = new LinkedHashMap<>();
        if (tools != null) {
            for (Tool t : tools) byId.put(t.spec().id(), t);
        }
        Map<String, Tool> copy = Map.copyOf(byId);
        return new ToolRegistry() {
            @Override public Optional<Tool> get(String id) { return Optional.ofNullable(copy.get(id)); }
            @Override public Set<String> ids() { return copy.keySet(); }
        };
    }
}
