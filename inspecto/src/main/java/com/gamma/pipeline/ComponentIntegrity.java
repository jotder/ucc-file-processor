package com.gamma.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-component referential-integrity rules (System Maintenance MNT-7/MNT-16), shared by the
 * {@code metadata_validate} maintenance task and the bundle-import pre-check. Rules are grounded in
 * the reference component shapes the demo space ships — never guessed: a widget's
 * {@code datasetId}/{@code queryId} must name an existing Dataset/Query; a dashboard tile's
 * {@code widgetId} must name an existing Widget; two components of one type must not be
 * content-identical apart from {@code name}. Extend here as further kinds declare their reference
 * keys. Pure functions over an in-memory universe — no I/O, so callers decide what the universe is
 * (the live registry, or registry ∪ an incoming bundle).
 */
public final class ComponentIntegrity {

    private ComponentIntegrity() {}

    /** Broken widget→Dataset/Query and dashboard-tile→Widget references over {@code byType}
     *  (missing type keys are treated as empty). Human-readable, stable finding strings. */
    public static List<String> brokenRefs(Map<String, List<ComponentRegistry.Component>> byType) {
        List<String> findings = new ArrayList<>();
        Set<String> datasets = names(byType.getOrDefault("dataset", List.of()));
        Set<String> queries = names(byType.getOrDefault("query", List.of()));
        Set<String> widgets = names(byType.getOrDefault("widget", List.of()));
        for (ComponentRegistry.Component w : byType.getOrDefault("widget", List.of())) {
            String datasetId = str(w.content().get("datasetId"));
            if (datasetId != null && !datasets.contains(datasetId))
                findings.add("broken reference: widget '" + w.name() + "' → missing dataset '" + datasetId + "'");
            String queryId = str(w.content().get("queryId"));
            if (queryId != null && !queries.contains(queryId))
                findings.add("broken reference: widget '" + w.name() + "' → missing query '" + queryId + "'");
        }
        for (ComponentRegistry.Component d : byType.getOrDefault("dashboard", List.of())) {
            if (!(d.content().get("tiles") instanceof List<?> tiles)) continue;
            for (Object t : tiles) {
                if (!(t instanceof Map<?, ?> tile)) continue;
                String widgetId = str(tile.get("widgetId"));
                if (widgetId != null && !widgets.contains(widgetId))
                    findings.add("broken reference: dashboard '" + d.name() + "' tile → missing widget '" + widgetId + "'");
            }
        }
        return findings;
    }

    /** Components of one type whose content is identical apart from {@code name}. */
    public static List<String> duplicates(Map<String, List<ComponentRegistry.Component>> byType) {
        List<String> findings = new ArrayList<>();
        for (Map.Entry<String, List<ComponentRegistry.Component>> e : byType.entrySet()) {
            Map<Map<String, Object>, String> seen = new HashMap<>();
            for (ComponentRegistry.Component c : e.getValue()) {
                Map<String, Object> body = new HashMap<>(c.content());
                body.remove("name");
                String first = seen.putIfAbsent(body, c.name());
                if (first != null && !first.equals(c.name()))
                    findings.add("duplicate definition: " + e.getKey() + "s '" + first + "' and '"
                            + c.name() + "' are identical apart from the name");
            }
        }
        return findings;
    }

    private static Set<String> names(List<ComponentRegistry.Component> list) {
        Set<String> out = new HashSet<>();
        for (ComponentRegistry.Component c : list) out.add(c.name());
        return out;
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
