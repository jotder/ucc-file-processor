package com.gamma.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Assembles a validated {@link ReconService.Spec} from a persisted/inline {@code reconciliation} config map
 * — the single place the {@code datasets} (with v1 {@code leftDataset}/{@code rightDataset} compatibility),
 * {@code keyColumns}, {@code compareColumns} (agg/tolerance), {@code columnMap} and {@code filters} are read.
 * Shared by the interactive {@code POST /recon/run} route ({@code ReconRoutes}) and the scheduled
 * {@code recon.run} Job so both build the <em>identical</em> spec — a reconciliation must reconcile the same
 * way whoever runs it. Relation-SQL resolution is injected ({@code relationSqlFor}) because the two callers
 * report an unknown/unusable dataset differently (an HTTP 404/422 vs a Job failure); anything it throws
 * propagates unchanged. A structurally-unusable config throws {@link IllegalArgumentException}.
 */
public final class ReconConfigLoader {
    private ReconConfigLoader() {}

    /** Build a spec from {@code config}, resolving each dataset id to its trusted relation SQL via {@code relationSqlFor}. */
    public static ReconService.Spec buildSpec(Map<String, Object> config, Function<String, String> relationSqlFor) {
        List<String> datasets = strings(config.get("datasets"));
        if (datasets.isEmpty()) {   // v1 config compatibility: leftDataset/rightDataset
            String left = str(config, "leftDataset");
            String right = str(config, "rightDataset");
            if (left != null) datasets.add(left);
            if (right != null) datasets.add(right);
        }
        if (datasets.size() < 2 || datasets.size() > 3)
            throw new IllegalArgumentException("expected 2 or 3 datasets (the first is the anchor), got " + datasets.size());

        Map<String, Object> columnMap = mapOf(config.get("columnMap"));
        Map<String, Object> filters = mapOf(config.get("filters"));
        List<ReconService.Side> sides = new ArrayList<>();
        for (String dsId : datasets)
            sides.add(new ReconService.Side(dsId, relationSqlFor.apply(dsId),
                    stringMap(columnMap.get(dsId)), strOrNull(filters.get(dsId))));

        List<ReconService.Measure> measures = new ArrayList<>();
        if (config.get("compareColumns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c) {
                    Map<String, Object> m = cast(c);
                    measures.add(new ReconService.Measure(
                            str(m, "column"),
                            orDefault(str(m, "agg"), "sum"),
                            orDefault(str(m, "toleranceType"), "exact"),
                            doubleOr(m.get("tolerance"), 0)));
                }
        boolean includeRecordCount = !(config.get("includeRecordCount") instanceof Boolean b) || b;

        return ReconService.Spec.of(sides, strings(config.get("keyColumns")), measures, includeRecordCount);
    }

    // ── config-map parsing helpers ────────────────────────────────────────────────

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
        Map<String, String> out = new java.util.LinkedHashMap<>();
        m.forEach((k, val) -> { if (val != null) out.put(String.valueOf(k), String.valueOf(val)); });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
    }

    private static String strOrNull(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static String orDefault(String v, String def) {
        return v == null ? def : v;
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
