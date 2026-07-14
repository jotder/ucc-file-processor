package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.ReconService;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reconciliation execution (DAT-7; design {@code docs/superpower/reconciliation-board-design.md}) —
 * {@code POST /recon/columns} (per-dataset column inventory + cross-side auto-matches for the unified
 * column list), {@code POST /recon/run} (the Board's grain rows + exact totals + Break summary) and
 * {@code POST /recon/breaks} (the paged Break sets, optionally scoped to a Board dimension path).
 * Each accepts a saved {@code reconciliation} component by {@code id} <em>or</em> an inline
 * {@code config} (the Board's draft mode). Authoring reuses the generic component CRUD
 * ({@code /components/reconciliation/{id}}); Break lifecycle (auto-close / preserved resolutions) stays
 * client-side per the C9 contract — these routes are stateless compute over {@link ReconService}.
 *
 * <p>Fail-closed: write root unset → 503; unknown reconciliation / dataset → 404; an unusable config
 * (dataset count, unsafe identifier, bad agg/tolerance, {@code ExpressionGuard}-rejected filter) or a
 * failing comparison query → 422. All comparison SQL is server-built — there is no caller-SQL surface.
 */
final class ReconRoutes implements RouteModule {

    private static final int GRAIN_LIMIT = 5_000;      // Board grain-row cap (MAX_LIMIT parity)
    private static final int DEFAULT_BREAKS_LIMIT = 200;
    private static final int MAX_LIMIT = 5_000;

    @Override
    public void register(ApiContext api) {
        api.post("/recon/columns", (e, m) -> columns(api, api.body(e)));
        api.post("/recon/run", (e, m) -> run(api, api.body(e)));
        api.post("/recon/breaks", (e, m) -> breaks(api, api.body(e)));
    }

    // ── POST /recon/columns {datasets:[ids]} ────────────────────────────────────────

    private Object columns(ApiContext api, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "reconciliation");
        List<String> ids = strings(body.get("datasets"));
        if (ids.isEmpty()) throw new ApiException(422, "missing 'datasets' (the dataset ids to inventory)");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        List<ReconService.Side> sides = new ArrayList<>();
        for (String id : ids)
            sides.add(new ReconService.Side(id, relationSql(api, store, writeRoot, id), null, null));
        try {
            return ReconService.columns(sides);
        } catch (SQLException e) {
            throw new ApiException(422, "column inventory failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ApiException(503, "query sandbox unavailable: " + e.getMessage());
        }
    }

    // ── POST /recon/run {id | config, limit?} ───────────────────────────────────────

    private Object run(ApiContext api, Map<String, Object> body) {
        ReconService.Spec spec = spec(api, body);
        int limit = clamp(intOr(body.get("limit"), GRAIN_LIMIT));
        ReconService.RunResult r;
        try {
            r = ReconService.run(spec, limit);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, "reconciliation failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ApiException(503, "query sandbox unavailable: " + e.getMessage());
        }
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", r.rows().size());
        statistics.put("elapsedMs", r.elapsedMs());
        statistics.put("truncated", r.truncated());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyColumns", spec.keyColumns());
        data.put("measures", measureNames(spec));
        data.put("rows", r.rows());
        data.put("totals", r.totals());
        data.put("summary", r.summary());
        data.put("statistics", statistics);
        return data;
    }

    // ── POST /recon/breaks {id | config, path?, type?, limit?, offset?} ─────────────

    private Object breaks(ApiContext api, Map<String, Object> body) {
        ReconService.Spec spec = spec(api, body);
        int limit = clamp(intOr(body.get("limit"), DEFAULT_BREAKS_LIMIT));
        int offset = Math.max(0, intOr(body.get("offset"), 0));
        String type = ApiContext.str(body, "type");
        // The compared side of the anchor-relative pair: "b" (default) or, on a 3-way spec, "c".
        String side = orDefault(ApiContext.str(body, "side"), "b");
        int other = "b".equals(side) ? 1 : "c".equals(side) ? 2 : -1;
        if (other < 0) throw new ApiException(422, "side must be b|c, got '" + side + "'");
        Map<String, String> path = pathOf(body.get("path"));
        Map<String, ReconService.BreakSet> sets;
        try {
            sets = ReconService.breaks(spec, path, type, other, limit, offset);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, "reconciliation failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ApiException(503, "query sandbox unavailable: " + e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, ReconService.BreakSet> e : sets.entrySet()) {
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("rows", e.getValue().rows());
            set.put("rowCount", e.getValue().rowCount());
            set.put("truncated", e.getValue().truncated());
            data.put(e.getKey(), set);
        }
        return data;
    }

    // ── spec assembly ───────────────────────────────────────────────────────────────

    /** Resolve the request's reconciliation config ({@code config} inline, or saved {@code id}) to a validated spec. */
    private ReconService.Spec spec(ApiContext api, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "reconciliation");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));

        Map<String, Object> config;
        if (body.get("config") instanceof Map<?, ?> inline) {
            config = cast(inline);
        } else {
            String id = ApiContext.str(body, "id");
            if (id == null) throw new ApiException(422, "provide a saved reconciliation 'id' or an inline 'config'");
            config = component(store, "reconciliation", id)
                    .orElseThrow(() -> new ApiException(404, "no reconciliation '" + id + "'"));
        }

        List<String> datasets = strings(config.get("datasets"));
        if (datasets.isEmpty()) {   // v1 config compatibility: leftDataset/rightDataset
            String left = ApiContext.str(config, "leftDataset");
            String right = ApiContext.str(config, "rightDataset");
            if (left != null) datasets.add(left);
            if (right != null) datasets.add(right);
        }
        if (datasets.size() < 2 || datasets.size() > 3)
            throw new ApiException(422, "expected 2 or 3 datasets (the first is the anchor), got " + datasets.size());

        Map<String, Object> columnMap = mapOf(config.get("columnMap"));
        Map<String, Object> filters = mapOf(config.get("filters"));
        List<ReconService.Side> sides = new ArrayList<>();
        for (String dsId : datasets)
            sides.add(new ReconService.Side(dsId, relationSql(api, store, writeRoot, dsId),
                    stringMap(columnMap.get(dsId)), strOrNull(filters.get(dsId))));

        List<ReconService.Measure> measures = new ArrayList<>();
        if (config.get("compareColumns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c) {
                    Map<String, Object> m = cast(c);
                    measures.add(new ReconService.Measure(
                            ApiContext.str(m, "column"),
                            orDefault(ApiContext.str(m, "agg"), "sum"),
                            orDefault(ApiContext.str(m, "toleranceType"), "exact"),
                            doubleOr(m.get("tolerance"), 0)));
                }
        boolean includeRecordCount = !(config.get("includeRecordCount") instanceof Boolean b) || b;

        try {
            return ReconService.Spec.of(sides, strings(config.get("keyColumns")), measures, includeRecordCount);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
    }

    /** A dataset component's trusted relation SQL — 404 unknown dataset, 422 unusable dataset config. */
    private static String relationSql(ApiContext api, ComponentStore store, Path writeRoot, String datasetId) {
        Map<String, Object> dataset = component(store, "dataset", datasetId)
                .orElseThrow(() -> new ApiException(404, "unknown dataset '" + datasetId + "'"));
        try {
            return DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, "dataset '" + datasetId + "': " + bad.getMessage());
        }
    }

    private static Optional<Map<String, Object>> component(ComponentStore store, String type, String id) {
        try {
            return store.get(type, id).map(ComponentRegistry.Component::content);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    // ── request parsing helpers ─────────────────────────────────────────────────────

    private List<String> measureNames(ReconService.Spec spec) {
        List<String> names = new ArrayList<>();
        for (ReconService.Measure m : spec.measures()) names.add(m.name());
        if (spec.includeRecordCount()) names.add(ReconService.RECORDS);
        return names;
    }

    private static Map<String, String> pathOf(Object v) {
        if (!(v instanceof Map<?, ?> m) || m.isEmpty()) return null;
        Map<String, String> path = new LinkedHashMap<>();
        m.forEach((k, val) -> { if (val != null) path.put(String.valueOf(k), String.valueOf(val)); });
        return path;
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list)
            for (Object o : list) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
        return out;
    }

    private static Map<String, Object> mapOf(Object v) {
        return v instanceof Map<?, ?> m ? cast(m) : Map.of();
    }

    private static Map<String, String> stringMap(Object v) {
        if (!(v instanceof Map<?, ?> m) || m.isEmpty()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, val) -> { if (val != null) out.put(String.valueOf(k), String.valueOf(val)); });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String strOrNull(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static String orDefault(String v, String def) {
        return v == null ? def : v;
    }

    private static int clamp(int l) {
        return Math.max(1, Math.min(MAX_LIMIT, l));
    }

    private static int intOr(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }

    private static double doubleOr(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) {
            try {
                return Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }
}
