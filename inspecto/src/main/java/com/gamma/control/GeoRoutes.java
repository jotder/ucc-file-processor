package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Geo Map Phase 4 backend: the real <b>server-side projection</b> the Geo Map studio's mock-first
 * {@code geo-projection} GeoSources were designed against ({@code docs/okf/frontend/features/geo-map.md};
 * plan {@code docs/archived-documents/plans-archive/geo-map-analysis-plan.md} Phase 4). The DuckDB-side
 * fold of {@code projectPoints}/{@code projectRoutes}, so a projection scales past the ~5k-point browser cap.
 *
 * <p>{@code POST /geo/projection} — body {@code {dataset, latCol, lonCol, entityCol?, kindCol?, timeCol?,
 * attrCols?, limit?}} → {@code {points:[{id,lat,lon,kind,label?,time?,attrs?}], truncated, skipped}}: each
 * dataset row with a valid WGS84 coordinate becomes a point. Rows whose lat/lon is missing, non-numeric, or
 * out of range are excluded and counted in {@code skipped} (mirrors the client's NaN-skip).
 *
 * <p>{@code POST /geo/routes} — body {@code {dataset, fromLatCol, fromLonCol, toLatCol, toLonCol, fromCol?,
 * toCol?, kindCol?, limit?}} → {@code {points, routes:[{id,from,to,kind,weight}], truncated, skipped}}: each
 * row is one origin→destination movement. Endpoints fold into points (by name, else rounded coordinate) and
 * rows fold into routes deduplicated per (origin, destination, kind) with a summed weight — the aggregation
 * runs as a DuckDB {@code GROUP BY}, so it scales far beyond a row-by-row browser fold.
 *
 * <p>Fail-closed like {@link InvRoutes}: write root unset → 503; unknown dataset → 404; a non-identifier
 * column or unusable dataset → 422. Column names are validated identifiers — no caller SQL text enters the
 * statement. Deliberately plain SQL: no DuckDB {@code spatial} extension (no geometry op is needed here, and
 * the hardened {@code SqlSandbox} disables extension loading) — see the plan's Phase 4 note.
 */
final class GeoRoutes implements RouteModule {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Mirrors the client's GEO_POINT_CAP; the server can scale far higher on request. */
    private static final int DEFAULT_LIMIT = 5_000;
    private static final int MAX_LIMIT = 100_000;

    @Override
    public void register(ApiContext api) {
        api.post("/geo/projection", (e, m) -> projection(api, api.body(e)));
        api.post("/geo/routes", (e, m) -> routes(api, api.body(e)));
    }

    // ── POST /geo/projection ────────────────────────────────────────────────────
    private Object projection(ApiContext api, Map<String, Object> body) throws IOException {
        Ctx c = context(api, body);
        String lat = ident(body, "latCol", true), lon = ident(body, "lonCol", true);
        String entity = ident(body, "entityCol", false);
        String kind = ident(body, "kindCol", false);
        String time = ident(body, "timeCol", false);
        List<String> attrCols = attrCols(body);
        int limit = limit(body);

        String valid = "TRY_CAST(" + q(lat) + " AS DOUBLE) BETWEEN -90 AND 90"
                + " AND TRY_CAST(" + q(lon) + " AS DOUBLE) BETWEEN -180 AND 180";
        StringBuilder sel = new StringBuilder("SELECT TRY_CAST(").append(q(lat)).append(" AS DOUBLE) AS lat")
                .append(", TRY_CAST(").append(q(lon)).append(" AS DOUBLE) AS lon");
        if (kind != null) sel.append(", CAST(").append(q(kind)).append(" AS VARCHAR) AS kind");
        if (entity != null) sel.append(", CAST(").append(q(entity)).append(" AS VARCHAR) AS label");
        if (time != null) {
            // epoch millis from a timestamp/date, else a numeric epoch as-is, else NULL (client parseTime parity).
            sel.append(", COALESCE(epoch_ms(TRY_CAST(").append(q(time)).append(" AS TIMESTAMP)), TRY_CAST(")
                    .append(q(time)).append(" AS BIGINT)) AS time");
        }
        for (int i = 0; i < attrCols.size(); i++) {
            sel.append(", CAST(").append(q(attrCols.get(i))).append(" AS VARCHAR) AS attr_").append(i);
        }
        sel.append(" FROM ").append(q(c.datasetId)).append(" WHERE ").append(valid);

        QueryExecutor.Result r = run(c, sel.toString(), limit);
        List<Map<String, Object>> points = new ArrayList<>(r.rows().size());
        int i = 0;
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("id", "pt:" + i++);
            pt.put("lat", row.get("lat"));
            pt.put("lon", row.get("lon"));
            pt.put("kind", nonBlankOr(kind != null ? row.get("kind") : null, "point"));
            if (entity != null) pt.put("label", row.get("label"));
            if (time != null) pt.put("time", row.get("time"));
            if (!attrCols.isEmpty()) {
                Map<String, Object> attrs = new LinkedHashMap<>();
                for (int a = 0; a < attrCols.size(); a++) attrs.put(attrCols.get(a), row.get("attr_" + a));
                pt.put("attrs", attrs);
            }
            points.add(pt);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points);
        out.put("routes", List.of());
        out.put("truncated", r.truncated());
        out.put("skipped", skipped(c, valid));
        return out;
    }

    // ── POST /geo/routes ──────────────────────────────────────────────────────────
    private Object routes(ApiContext api, Map<String, Object> body) throws IOException {
        Ctx c = context(api, body);
        String fromLat = ident(body, "fromLatCol", true), fromLon = ident(body, "fromLonCol", true);
        String toLat = ident(body, "toLatCol", true), toLon = ident(body, "toLonCol", true);
        String fromCol = ident(body, "fromCol", false), toCol = ident(body, "toCol", false);
        String kindCol = ident(body, "kindCol", false);
        int limit = limit(body);

        String aName = fromCol != null ? "TRIM(CAST(" + q(fromCol) + " AS VARCHAR))" : "''";
        String bName = toCol != null ? "TRIM(CAST(" + q(toCol) + " AS VARCHAR))" : "''";
        String kindExpr = kindCol != null ? "NULLIF(TRIM(CAST(" + q(kindCol) + " AS VARCHAR)), '')" : "NULL";
        String valid = "a_lat BETWEEN -90 AND 90 AND a_lon BETWEEN -180 AND 180"
                + " AND b_lat BETWEEN -90 AND 90 AND b_lon BETWEEN -180 AND 180";
        // The per-row TRY_CAST relation, shared by the aggregation and the skipped counter.
        String rCte = "WITH r AS (SELECT"
                + " TRY_CAST(" + q(fromLat) + " AS DOUBLE) AS a_lat, TRY_CAST(" + q(fromLon) + " AS DOUBLE) AS a_lon,"
                + " TRY_CAST(" + q(toLat) + " AS DOUBLE) AS b_lat, TRY_CAST(" + q(toLon) + " AS DOUBLE) AS b_lon,"
                + " " + aName + " AS a_name, " + bName + " AS b_name, " + kindExpr + " AS kind"
                + " FROM " + q(c.datasetId) + ")";
        // Endpoint identity = name, else the rounded coordinate (matches the client's `${lat.toFixed(4)}, ...`).
        String sql = rCte
                + " SELECT COALESCE(NULLIF(a_name, ''), printf('%.4f, %.4f', a_lat, a_lon)) AS a_label,"
                + " COALESCE(NULLIF(b_name, ''), printf('%.4f, %.4f', b_lat, b_lon)) AS b_label,"
                + " kind, ANY_VALUE(a_lat) AS a_lat, ANY_VALUE(a_lon) AS a_lon,"
                + " ANY_VALUE(b_lat) AS b_lat, ANY_VALUE(b_lon) AS b_lon, COUNT(*) AS weight"
                + " FROM r WHERE " + valid
                + " GROUP BY a_label, b_label, kind ORDER BY weight DESC, a_label, b_label";

        QueryExecutor.Result r = run(c, sql, limit);
        Map<String, Map<String, Object>> points = new LinkedHashMap<>();
        List<Map<String, Object>> routeList = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            String from = endpoint(points, row.get("a_label"), row.get("a_lat"), row.get("a_lon"));
            String to = endpoint(points, row.get("b_label"), row.get("b_lat"), row.get("b_lon"));
            String kind = (String) nonBlankOr(row.get("kind"), "route");
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("id", from + "->" + to + ":" + kind);
            route.put("from", from);
            route.put("to", to);
            route.put("kind", kind);
            route.put("weight", row.get("weight"));
            routeList.add(route);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", new ArrayList<>(points.values()));
        out.put("routes", routeList);
        out.put("truncated", r.truncated());
        out.put("skipped", skipped(c, rCte + " SELECT * FROM r WHERE ", valid));
        return out;
    }

    /** Fold an endpoint into the shared points map (id {@code ep:<label>}); returns the point id. */
    private static String endpoint(Map<String, Map<String, Object>> points, Object label, Object lat, Object lon) {
        String id = "ep:" + label;
        points.computeIfAbsent(id, k -> {
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("id", id);
            pt.put("lat", lat);
            pt.put("lon", lon);
            pt.put("kind", "place");
            pt.put("label", label);
            return pt;
        });
        return id;
    }

    // ── shared helpers ────────────────────────────────────────────────────────────

    /** Resolved dataset + its trusted relation SQL, or a fail-closed {@link ApiException}. */
    private record Ctx(String datasetId, String relationSql) {}

    private Ctx context(ApiContext api, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "geo projection");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, "body must include 'dataset'");
        Map<String, Object> dataset = new ComponentStore(writeRoot.resolve("registry")).get("dataset", datasetId)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "no dataset '" + datasetId + "'"));
        try {
            return new Ctx(datasetId,
                    DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views"))));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
    }

    private static QueryExecutor.Result run(Ctx c, String sql, int limit) {
        try {
            return QueryExecutor.run(new QueryExecutor.Request(
                    c.datasetId, c.relationSql, sql, limit, 0, List.of(), List.of()));
        } catch (SQLException e) {
            throw new ApiException(422, "projection failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ApiException(422, "projection failed: " + e.getMessage());
        }
    }

    /** Rows excluded for an invalid coordinate — a scalar COUNT over the same validity predicate. */
    private long skipped(Ctx c, String validPredicate) {
        return skipped(c, "SELECT * FROM " + q(c.datasetId) + " WHERE ", validPredicate);
    }

    /**
     * Count rows the projection dropped: everything for which {@code validPredicate} is not TRUE (NULL
     * coordinates included). {@code fromClausePrefix} ends just before the predicate so points and routes
     * share one counter over their own (possibly CTE-wrapped) relation.
     */
    private long skipped(Ctx c, String fromClausePrefix, String validPredicate) {
        String base = fromClausePrefix + "(" + validPredicate + ") IS NOT TRUE";
        String count = "SELECT COUNT(*) AS skipped FROM (" + base + ") AS __s";
        QueryExecutor.Result r = run(c, count, 1);
        Object v = r.rows().isEmpty() ? 0L : r.rows().get(0).get("skipped");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static Object nonBlankOr(Object v, String fallback) {
        String s = v == null ? "" : String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static int limit(Map<String, Object> body) {
        return body.get("limit") instanceof Number n
                ? Math.max(1, Math.min(MAX_LIMIT, n.intValue())) : DEFAULT_LIMIT;
    }

    private static List<String> attrCols(Map<String, Object> body) {
        Object raw = body.get("attrCols");
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            String v = String.valueOf(o);
            if (!SAFE_IDENT.matcher(v).matches())
                throw new ApiException(422, "unsafe column identifier '" + v + "' for attrCols");
            out.add(v);
        }
        return out;
    }

    private static String ident(Map<String, Object> body, String key, boolean required) {
        String v = ApiContext.str(body, key);
        if (v == null) {
            if (required) throw new ApiException(422, "body must include '" + key + "'");
            return null;
        }
        if (!SAFE_IDENT.matcher(v).matches())
            throw new ApiException(422, "unsafe column identifier '" + v + "' for " + key);
        return v;
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
