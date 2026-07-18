package com.gamma.query;

import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.sql.SqlViews;

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
 *
 * <p><b>Calculated columns (DAT-5).</b> A dataset may declare {@code calculated: [{name, expr}]} —
 * row-level derived columns computed at query time. Each {@code expr} is a caller-authored SQL
 * <em>fragment</em> and must pass {@link ExpressionGuard} (fragment-level safety; design:
 * {@code docs/superpower/calculated-columns-design.md}); each {@code name} must be a plain identifier.
 * The base relation is then wrapped {@code SELECT *, (expr) AS "name", … FROM (<base>)} — so every
 * consumer (BI query, reports, alerts, materialization) sees calculated columns as real columns.
 * Fail-closed: one bad column makes the whole dataset unusable (422), never silently degraded.
 */
public final class DatasetRelation {

    private DatasetRelation() {}

    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * @param datasetConfig the dataset component's parsed config
     * @param dataRoot      the space's data directory (for {@code physicalRef}); may be {@code null} for view-backed datasets
     * @param views         the space's view store (for {@code view}-backed datasets)
     * @throws IllegalArgumentException on an unusable dataset config (→ 422 at the route)
     */
    public static String relationSql(Map<String, Object> datasetConfig, Path dataRoot, ViewStore views) {
        return withCalculated(baseRelationSql(datasetConfig, dataRoot, views), datasetConfig);
    }

    private static String baseRelationSql(Map<String, Object> datasetConfig, Path dataRoot, ViewStore views) {
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
            if (ref.contains("..") || !SAFE_REF.matcher(ref).matches())
                throw new IllegalArgumentException("unsafe dataset physicalRef '" + ref + "'");
            // A shared/<owner>/<item> ref routes to the owner's Exchange snapshot (grant-checked, fail-closed)
            // instead of this space's data root — everything downstream reads it as an ordinary Parquet glob.
            Path base = ref.startsWith(SHARED_PREFIX)
                    ? resolveShared(ref)
                    : localBase(ref, dataRoot);
            String root = base.normalize().toString().replace('\\', '/');
            // Store-layout contract: a pipeline-shaped store (one with a database/ subtree) reads its
            // mapped output only — quarantine/backup/nested trees stay out of the dataset. An explicit
            // deeper ref (orders/database, orders/rollup) resolves as written.
            if (!ref.startsWith(SHARED_PREFIX)) root = SqlViews.storeReadRoot(root);
            String glob = root + "/**/*.parquet";
            return "SELECT * FROM read_parquet(" + sqlStr(glob) + ")";
        }
        throw new IllegalArgumentException("dataset must declare a 'view' or a 'physicalRef'");
    }

    private static final String SHARED_PREFIX = "shared/";

    private static Path localBase(String ref, Path dataRoot) {
        if (dataRoot == null)
            throw new IllegalArgumentException("no data root for this space; cannot resolve physicalRef");
        return dataRoot.resolve(ref);
    }

    /** Resolve a {@code shared/<owner>/<item>} ref to its snapshot dir via the installed {@link SharedRefResolver}. */
    private static Path resolveShared(String ref) {
        String[] parts = ref.substring(SHARED_PREFIX.length()).split("/", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty())
            throw new IllegalArgumentException("malformed shared ref '" + ref + "' (expected shared/<owner>/<item>)");
        return SharedRefResolver.global().resolveSnapshot(parts[0], parts[1])
                .orElseThrow(() -> new IllegalArgumentException(
                        "shared dataset '" + ref + "' is not available (no active grant, or no snapshot published yet)"));
    }

    /** Wrap {@code base} with the dataset's calculated columns (DAT-5), or return it untouched when none. */
    private static String withCalculated(String base, Map<String, Object> datasetConfig) {
        Object calc = datasetConfig == null ? null : datasetConfig.get("calculated");
        if (!(calc instanceof java.util.List<?> list) || list.isEmpty()) return base;
        StringBuilder cols = new StringBuilder();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> c))
                throw new IllegalArgumentException("calculated entries must be {name, expr} objects");
            String name = str(cast(c), "name");
            String expr = str(cast(c), "expr");
            if (name == null || !SAFE_IDENT.matcher(name).matches())
                throw new IllegalArgumentException("calculated column needs a plain-identifier 'name', got '" + name + "'");
            if (expr == null)
                throw new IllegalArgumentException("calculated column '" + name + "' needs an 'expr'");
            cols.append(", (").append(ExpressionGuard.check(expr)).append(") AS \"").append(name).append('"');
        }
        return "SELECT *" + cols + " FROM (" + base + ") AS __base";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static String sqlStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
