package com.gamma.query;

import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves a {@code dataset} component's config to a <b>relation SQL</b> string usable as
 * {@code CREATE VIEW <name> AS <relationSql>} (W4; design §6 context 7). This is the query-time
 * <em>read</em> path — it does not materialize anything (Matrices materialization stays a separate
 * backend-backlog concern). Two forms:
 * <ul>
 *   <li>{@code {view: "<store>"}} — the flow-produced {@link ViewDefinition}'s
 *       {@link ViewDefinition#derivedSql() derived SQL} (which already embeds its physical paths).</li>
 *   <li>{@code {physicalRef: "<store>"[, format]}} — a {@code read_parquet('<dataDir>/<store>/**')} glob
 *       over the space's at-rest data (the same physical layout {@code ViewQuery} reads).</li>
 * </ul>
 * The returned SQL is <b>trusted</b> (server-built) and is the only place file-reading functions appear —
 * a query's own text is {@code SqlGuard}-checked and can never smuggle one.
 */
public final class DatasetRelation {

    private DatasetRelation() {}

    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");

    /**
     * @param datasetConfig the dataset component's parsed config
     * @param dataRoot      the space's data directory (for {@code physicalRef}); may be {@code null} for view-backed datasets
     * @param views         the space's view store (for {@code view}-backed datasets)
     * @throws IllegalArgumentException on an unusable dataset config (→ 422 at the route)
     */
    public static String relationSql(Map<String, Object> datasetConfig, Path dataRoot, ViewStore views) {
        String view = str(datasetConfig, "view");
        if (view != null) {
            Optional<ViewDefinition> def = views == null ? Optional.empty() : views.get(view);
            String sql = def.map(ViewDefinition::derivedSql).orElse(null);
            if (def.isEmpty())
                throw new IllegalArgumentException("dataset references unknown view '" + view + "'");
            if (sql == null || sql.isBlank())
                throw new IllegalArgumentException("view '" + view + "' has no derived SQL to query");
            return sql;
        }
        String ref = str(datasetConfig, "physicalRef");
        if (ref != null) {
            if (dataRoot == null)
                throw new IllegalArgumentException("no data root for this space; cannot resolve physicalRef");
            if (ref.contains("..") || !SAFE_REF.matcher(ref).matches())
                throw new IllegalArgumentException("unsafe dataset physicalRef '" + ref + "'");
            String glob = dataRoot.resolve(ref).normalize().toString().replace('\\', '/') + "/**/*.parquet";
            return "SELECT * FROM read_parquet(" + sqlStr(glob) + ")";
        }
        throw new IllegalArgumentException("dataset must declare a 'view' or a 'physicalRef'");
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static String sqlStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
