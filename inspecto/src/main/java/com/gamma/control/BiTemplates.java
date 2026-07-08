package com.gamma.control;

import com.gamma.pipeline.ComponentStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The curated widget/dashboard template gallery (BI-8): starter component sets an operator applies to
 * their own Dataset. Templates are parameterized ({@code dataset} required, {@code prefix} optional for
 * id-collision-free re-application) and written server-side through the ordinary {@link ComponentStore} —
 * so an applied template is immediately editable in the Studio and exportable via {@code /bundle/export}
 * (which stays the cross-space sharing mechanism; this gallery is in-instance curation).
 *
 * <p>Fail-closed: apply is authoring-gated at the route, 404 unknown template, 409 when any target
 * component id already exists (use {@code prefix} to disambiguate), 422 on a missing param.
 */
final class BiTemplates {

    /** One curated template: metadata + the parameterized components it writes. */
    private record Template(String id, String title, String description,
                            List<Map<String, Object>> components) {}

    private static final String P_DATASET = "${dataset}";
    private static final String P_PREFIX = "${prefix}";

    // Component content is authored in the SAME shape the Studio consumes (widget-types.ts / dashboard-types.ts),
    // so an applied template is immediately renderable and editable: a widget = {vizType, datasetId, controls, options},
    // a dashboard = {name, tiles:[{widgetId, span}]}. Curated over the conventional starter columns
    // (region/amount/id) — a starter board the operator then edits to their dataset's real fields.
    private static final List<Template> TEMPLATES = List.of(
            new Template("kpi-overview", "KPI overview",
                    "A count KPI, a sum-by-dimension bar, and a per-dimension table over one Dataset — the "
                            + "minimal executive board to start from.",
                    List.of(
                            widget(P_PREFIX + "kpi_total", "kpi", "Total records",
                                    Map.of("value", List.of(measure("count", "id")))),
                            widget(P_PREFIX + "sum_by_dim", "bar", "Sum by dimension",
                                    Map.of("x", List.of(dimension("region")),
                                            "y", List.of(measure("sum", "amount")))),
                            widget(P_PREFIX + "raw_table", "table", "By dimension",
                                    Map.of("x", List.of(dimension("region")),
                                            "y", List.of(measure("sum", "amount")))),
                            dashboard(P_PREFIX + "kpi_board", "KPI overview",
                                    tile(P_PREFIX + "kpi_total", 1), tile(P_PREFIX + "sum_by_dim", 2),
                                    tile(P_PREFIX + "raw_table", 1)))),
            new Template("quality-monitor", "Data quality monitor",
                    "Row volume by dimension plus a distinct-key count — a starting point for watching a "
                            + "feed's health.",
                    List.of(
                            widget(P_PREFIX + "volume", "bar", "Row volume by dimension",
                                    Map.of("x", List.of(dimension("region")),
                                            "y", List.of(measure("count", "id")))),
                            widget(P_PREFIX + "distincts", "kpi", "Distinct keys",
                                    Map.of("value", List.of(measure("countDistinct", "id")))),
                            dashboard(P_PREFIX + "quality_board", "Data quality",
                                    tile(P_PREFIX + "volume", 2), tile(P_PREFIX + "distincts", 1)))));

    private BiTemplates() {}

    /** {@code GET /bi/templates} — the gallery listing (metadata only; content comes with apply). */
    static Object list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Template t : TEMPLATES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("title", t.title());
            m.put("description", t.description());
            m.put("params", List.of("dataset", "prefix?"));
            m.put("components", t.components().stream().map(c ->
                    Map.of("kind", c.get("kind"), "id", c.get("id"))).toList());
            out.add(m);
        }
        return out;
    }

    /** {@code POST /bi/templates/{id}/apply} — body {@code {dataset, prefix?}}; writes the components. */
    static Object apply(ApiContext api, String templateId, Map<String, Object> body) throws IOException {
        Template t = TEMPLATES.stream().filter(x -> x.id().equals(templateId)).findFirst()
                .orElseThrow(() -> new ApiException(404, "no template '" + templateId + "'"));
        String dataset = ApiContext.str(body, "dataset");
        if (dataset == null) throw new ApiException(422, "apply body must include 'dataset'");
        String rawPrefix = ApiContext.str(body, "prefix");
        String prefix = rawPrefix == null ? "" : rawPrefix.trim() + "_";

        Path writeRoot = WriteGates.requireWriteRoot(api, "template apply");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.get("dataset", dataset).orElseThrow(() -> new ApiException(404, "no dataset '" + dataset + "'"));

        // Conflict check first — apply is all-or-nothing, never a partial board.
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Map<String, Object> c : t.components()) {
            String kind = (String) c.get("kind");
            String id = substitute((String) c.get("id"), dataset, prefix);
            if (store.get(kind, id).isPresent())
                throw new ApiException(409, kind + " '" + id + "' already exists — re-apply with a 'prefix'");
            Map<String, Object> content = substituteTree(asMap(c.get("content")), dataset, prefix);
            resolved.add(Map.of("kind", kind, "id", id, "content", content));
        }
        List<Map<String, Object>> written = new ArrayList<>();
        for (Map<String, Object> c : resolved) {
            store.write((String) c.get("kind"), (String) c.get("id"), asMap(c.get("content")));
            written.add(Map.of("kind", c.get("kind"), "id", c.get("id")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", templateId);
        out.put("dataset", dataset);
        out.put("created", written);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Map<String, Object> component(String kind, String id, Map<String, Object> content) {
        return Map.of("kind", kind, "id", id, "content", content);
    }

    /** A {@code widget} component in the Studio's {@code {vizType, datasetId, controls, options}} shape. */
    private static Map<String, Object> widget(String id, String vizType, String title, Map<String, Object> controls) {
        return component("widget", id, Map.of(
                "vizType", vizType, "datasetId", P_DATASET, "controls", controls,
                "options", Map.of("title", title)));
    }

    /** A {@code dashboard} component in the Studio's {@code {name, tiles:[{widgetId, span}]}} shape. */
    @SafeVarargs
    private static Map<String, Object> dashboard(String id, String name, Map<String, Object>... tiles) {
        return component("dashboard", id, Map.of("name", name, "tiles", List.of(tiles), "filter", Map.of()));
    }

    private static Map<String, Object> tile(String widgetId, int span) {
        return Map.of("widgetId", widgetId, "span", span);
    }

    /** One measure channel value ({@code agg(field)}); {@code count} ignores the field but keeps the shape valid. */
    private static Map<String, Object> measure(String agg, String field) {
        return Map.of("field", field, "agg", agg);
    }

    /** One dimension channel value (no aggregation). */
    private static Map<String, Object> dimension(String field) {
        return Map.of("field", field);
    }

    private static String substitute(String s, String dataset, String prefix) {
        return s.replace(P_DATASET, dataset).replace(P_PREFIX, prefix);
    }

    @SuppressWarnings("unchecked")
    private static Object substituteAny(Object v, String dataset, String prefix) {
        if (v instanceof String s) return substitute(s, dataset, prefix);
        if (v instanceof Map<?, ?> m) return substituteTree((Map<String, Object>) m, dataset, prefix);
        if (v instanceof List<?> l) return l.stream().map(x -> substituteAny(x, dataset, prefix)).toList();
        return v;
    }

    private static Map<String, Object> substituteTree(Map<String, Object> m, String dataset, String prefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, substituteAny(v, dataset, prefix)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
