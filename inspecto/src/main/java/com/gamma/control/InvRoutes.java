package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Investigation-studio backend (INV-1): the real <b>Entity Projection</b> over a Dataset — the DuckDB-side
 * fold the Link Analysis studio's mock-first {@code entity-projection} GraphSource was designed against
 * ({@code docs/superpower/link-analysis-and-graphsource.md} §7).
 *
 * <p>{@code POST /inv/projection} — body {@code {dataset, sourceCol, targetCol, linkKindCol?, attrCols?, limit?}}
 * → {@code {rows:[{source,target,kind,count,attrs?}], truncated}}: distinct {@code (source, target[, kind]
 * [, ...attrCols])} tuples with folded row counts, heaviest first. When {@code attrCols} is given, each
 * column joins the fold key — a folded edge with differing attribute values across rows becomes separate
 * output rows, one per distinct attribute combination, consistent with the "identical tuples fold" contract
 * above (no attribute value is silently dropped). The G6 node/edge <em>presentation</em> fold (entity nodes,
 * edge kind·count labels, the 500-node cap) deliberately stays client-side where it already lives — this
 * endpoint is the aggregation, so the projection scales to Datasets far beyond what the browser could fold
 * row-by-row.
 *
 * <p>{@code POST /inv/projection/neighbors} (Phase E, incremental expand) — same body plus a required
 * {@code value}: the one-hop neighborhood of that entity (rows where it's either endpoint), so the
 * Studio's "expand node" action can grow the canvas without re-fetching the whole relation.
 *
 * <p>Fail-closed like {@link BiRoutes}: write root unset → 503; unknown dataset → 404; a non-identifier
 * column or unusable dataset → 422. Column names are validated identifiers — no caller SQL text enters
 * the statement — and NULL endpoints are excluded (a link needs both ends).
 */
final class InvRoutes implements RouteModule {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int DEFAULT_LIMIT = 2_000;
    private static final int MAX_LIMIT = 20_000;

    @Override
    public void register(ApiContext api) {
        api.post("/inv/projection", (e, m) -> project(api, e, api.body(e), null));
        api.post("/inv/projection/neighbors", (e, m) -> neighbors(api, e, api.body(e)));
    }

    /**
     * {@code POST /inv/projection/neighbors} — body adds a required {@code value}: the one-hop
     * neighborhood of that entity value (rows where it appears as either endpoint), for Link Analysis
     * Studio's incremental "expand node" action (Phase E). Same shape/gates as {@link #project}, just
     * pre-filtered server-side instead of returning the whole relation.
     */
    private Object neighbors(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        String value = ApiContext.str(body, "value");
        if (value == null) throw new ApiException(422, "body must include 'value'");
        return project(api, ex, body, value);
    }

    private Object project(ApiContext api, HttpExchange ex, Map<String, Object> body, String neighborsOf) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity projection");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String kindCol = ident(body, "linkKindCol", false);
        List<String> attrCols = attrCols(body);
        int limit = body.get("limit") instanceof Number n
                ? Math.max(1, Math.min(MAX_LIMIT, n.intValue())) : DEFAULT_LIMIT;

        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> dataset = store.get("dataset", datasetId)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "no dataset '" + datasetId + "'"));
        String relationSql;
        try {
            relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(),
                    new ViewStore(writeRoot.resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }

        // Server-built from validated identifiers only; one extra row detects truncation.
        String src = q(sourceCol), tgt = q(targetCol);
        String kindSel = kindCol != null ? ", CAST(" + q(kindCol) + " AS VARCHAR) AS kind" : "";
        StringBuilder attrSel = new StringBuilder();
        for (int i = 0; i < attrCols.size(); i++) {
            attrSel.append(", CAST(").append(q(attrCols.get(i))).append(" AS VARCHAR) AS attr_").append(i);
        }
        StringBuilder groupBy = new StringBuilder("GROUP BY 1, 2");
        int nextGroupIdx = 3;
        if (kindCol != null) groupBy.append(", ").append(nextGroupIdx++);
        for (int i = 0; i < attrCols.size(); i++) groupBy.append(", ").append(nextGroupIdx++);
        // No bind-parameter support in QueryExecutor.Request — the value is a literal, SQL-escaped
        // (doubled quotes), never caller SQL text; column identifiers are separately validated above.
        String neighborFilter = neighborsOf != null
                ? " AND (CAST(" + src + " AS VARCHAR) = '" + sqlLiteral(neighborsOf) + "'"
                + " OR CAST(" + tgt + " AS VARCHAR) = '" + sqlLiteral(neighborsOf) + "')" : "";
        String sql = "SELECT CAST(" + src + " AS VARCHAR) AS source, CAST(" + tgt + " AS VARCHAR) AS target"
                + kindSel + attrSel + ", COUNT(*) AS cnt FROM " + q(datasetId)
                + " WHERE " + src + " IS NOT NULL AND " + tgt + " IS NOT NULL" + neighborFilter
                + " " + groupBy
                + " ORDER BY cnt DESC, source, target";

        try {
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    datasetId, relationSql, sql, limit, 0, List.of(), List.of()));
            List<Map<String, Object>> rows = new ArrayList<>(r.rows().size());
            for (Map<String, Object> row : r.rows()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("source", row.get("source"));
                out.put("target", row.get("target"));
                out.put("kind", kindCol != null ? row.get("kind") : null);
                out.put("count", row.get("cnt"));
                if (!attrCols.isEmpty()) {
                    Map<String, Object> attrs = new LinkedHashMap<>();
                    for (int i = 0; i < attrCols.size(); i++) attrs.put(attrCols.get(i), row.get("attr_" + i));
                    out.put("attrs", attrs);
                }
                rows.add(out);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", rows);
            out.put("truncated", r.truncated());
            return out;
        } catch (SQLException e) {
            throw new ApiException(422, "projection failed: " + e.getMessage());
        }
    }

    /** SQL single-quote escaping for a literal value (doubled quotes) — never caller SQL text. */
    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    /** Optional {@code attrCols: string[]} — each validated as a safe identifier. */
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
