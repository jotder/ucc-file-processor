package com.gamma.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalog metadata for one Job Type (§6.1): a stable {@code id} (the {@code type:} string), a human
 * title/description, the {@link ParameterDecl}s it needs (R3 — the "query a Job Type for its required
 * parameters" interface), the signal types it may {@code emit}, and the {@link ArtifactDecl}s it may
 * record. Served by {@code GET /jobs/types/{id}} to drive authoring forms and wiring.
 */
public record JobTypeDescriptor(String id, String title, String description,
                                List<ParameterDecl> parameters, List<String> emits,
                                List<ArtifactDecl> artifacts) {

    public JobTypeDescriptor {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        emits      = emits == null ? List.of() : List.copyOf(emits);
        artifacts  = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    /** Convenience for a type with no emitted signals / artifacts declared. */
    public JobTypeDescriptor(String id, String title, String description, List<ParameterDecl> parameters) {
        this(id, title, description, parameters, List.of(), List.of());
    }

    /** JSON view for the API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("description", description);
        m.put("parameters", parameters.stream().map(p -> {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("type", p.type().name());
            pm.put("required", p.required());
            pm.put("deduce", p.deduce() == null ? "" : p.deduce());
            pm.put("default", p.defaultValue() == null ? "" : p.defaultValue());
            pm.put("description", p.description() == null ? "" : p.description());
            return pm;
        }).toList());
        m.put("emits", emits);
        m.put("artifacts", artifacts.stream()
                .map(a -> Map.of("name", a.name(), "kind", a.kind())).toList());
        return m;
    }
}
