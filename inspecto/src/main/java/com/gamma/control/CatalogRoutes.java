package com.gamma.control;

import com.gamma.catalog.EdgeKind;
import com.gamma.catalog.MetadataEdge;
import com.gamma.catalog.MetadataGraph;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metadata graph / data catalog routes ({@code /catalog*}, v3.2.0): the table list, the KPI catalog,
 * a filtered subgraph traverse, and one node's detail + neighbours. Extracted verbatim from
 * {@link ControlApi}: identical routes, query params, statuses and traversal defaults.
 */
final class CatalogRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/catalog", (e, m) -> api.service().catalog().tables());
        api.get("/catalog/kpis", (e, m) -> catalogKpis(api));
        api.get("/catalog/graph", (e, m) -> api.service().catalog().traverse(
                ApiContext.query(e, "from"),
                ApiContext.parseIntOr(ApiContext.query(e, "depth"), 1),
                direction(ApiContext.query(e, "direction")),
                nodeKinds(ApiContext.query(e, "kinds")),
                edgeKinds(ApiContext.query(e, "edgeKinds")),
                "true".equalsIgnoreCase(ApiContext.query(e, "overlay"))));
        api.get("/catalog/tables/(.+)", (e, m) -> catalogNodeDetail(api, ApiContext.name(m)));
    }

    /** A node (any kind) with its operational overlay + immediate neighbours, or 404. */
    private Map<String, Object> catalogNodeDetail(ApiContext api, String id) {
        MetadataGraphService catalog = api.service().catalog();
        MetadataNode node = catalog.hydrated(id);
        if (node == null) throw new ApiException(404, "no catalog node '" + id + "'");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        // depth 2 reaches an event table's schema (1) and its columns (2), plus lineage neighbours
        out.put("neighbors", catalog.traverse(id, 2, MetadataGraphService.Direction.BOTH, null, null, false));
        return out;
    }

    /** The KPI catalog (each KPI with its resolved inputs) + merged domain notes. */
    private Map<String, Object> catalogKpis(ApiContext api) {
        MetadataGraphService catalog = api.service().catalog();
        MetadataGraph g = catalog.structural();
        List<Map<String, Object>> kpis = new ArrayList<>();
        for (MetadataNode k : catalog.nodesOfKind(NodeKind.KPI)) {
            List<String> inputs = new ArrayList<>();
            for (MetadataEdge edge : g.edges()) {
                if (edge.kind() == EdgeKind.COMPUTED_FROM && edge.from().equals(k.id())) inputs.add(edge.to());
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", k.id());
            e.put("name", k.label());
            e.put("definition", k.attrs().get("definition"));
            e.put("grain", k.attrs().get("grain"));
            e.put("joinKeys", k.attrs().getOrDefault("joinKeys", List.of()));
            e.put("inputs", inputs);
            kpis.add(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", kpis);
        out.put("domain", catalog.domain());
        return out;
    }

    private static MetadataGraphService.Direction direction(String s) {
        if (s == null || s.isBlank()) return MetadataGraphService.Direction.BOTH;
        try {
            return MetadataGraphService.Direction.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid direction '" + s + "' (out|in|both)");
        }
    }

    private static Set<NodeKind> nodeKinds(String csv) {
        if (csv == null || csv.isBlank()) return null;
        EnumSet<NodeKind> set = EnumSet.noneOf(NodeKind.class);
        for (String t : csv.split(",")) {
            if (t.isBlank()) continue;
            try {
                set.add(NodeKind.valueOf(t.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "invalid node kind '" + t.trim() + "'");
            }
        }
        return set;
    }

    private static Set<EdgeKind> edgeKinds(String csv) {
        if (csv == null || csv.isBlank()) return null;
        EnumSet<EdgeKind> set = EnumSet.noneOf(EdgeKind.class);
        for (String t : csv.split(",")) {
            if (t.isBlank()) continue;
            try {
                set.add(EdgeKind.valueOf(t.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "invalid edge kind '" + t.trim() + "'");
            }
        }
        return set;
    }
}
