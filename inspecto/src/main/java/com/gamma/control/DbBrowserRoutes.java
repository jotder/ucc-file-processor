package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.sql.SqlGuard;
import com.gamma.sql.SqlViews;
import com.gamma.util.BrowsableStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Raw table browser — a per-space "database client" over the space's data
 * ({@code GET /db/catalog}, {@code GET /db/table}, {@code POST /db/query}; design
 * {@code docs/superpower/db-browser-design.md}). Phase 1 browses the <b>business-data</b> stores: the
 * Hive-partitioned Parquet/CSV directories under the space's {@code dataDir}. Each store is read through
 * the same trusted-relation-registered-as-a-view + {@link SqlGuard}-checked-user-SQL split the authored
 * query path uses ({@link QueryRoutes}), executed in an ephemeral DuckDB sandbox ({@link QueryExecutor}),
 * so there is no lock contention with the live engine and no new query engine.
 *
 * <p>Fail-closed: write root unset → 503; unknown / non-catalogued store → 404; a store path that escapes
 * the data root → 403; SQL that fails the {@link SqlGuard} allow-list → 422.
 *
 * <p>Operational (control-plane DB) tables are browsed through each live store's own connection via the
 * {@link BrowsableStore} seam (single-writer lock ⇒ no fresh connection; reads run {@code synchronized}
 * on the store). They appear only when a capability runs on a {@code db}/{@code postgres} backend — the
 * operational catalog is empty on the default in-memory/file backends.
 */
final class DbBrowserRoutes implements RouteModule {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 5_000;

    /** The one business-data group id (Parquet/CSV stores under {@code dataDir}). */
    private static final String STORES_GROUP = "stores";

    @Override
    public void register(ApiContext api) {
        api.get("/db/catalog", (e, m) -> catalog(api));
        api.get("/db/table", (e, m) -> table(api, e));
        api.post("/db/query", (e, m) -> query(api, e, api.body(e)));
    }

    // ── GET /db/catalog ──────────────────────────────────────────────────────────

    private Object catalog(ApiContext api) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "table browser");
        Map<String, String> datasetByRef = datasetByRef(writeRoot);

        List<Map<String, Object>> tables = new ArrayList<>();
        Path dataRoot = api.dataRoot();
        if (dataRoot != null && Files.isDirectory(dataRoot)) {
            try (Stream<Path> dirs = Files.list(dataRoot)) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".")) continue;
                    String format = detectFormat(dir);
                    if (format == null) continue;   // no parquet/csv data → not a browsable store
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", name);
                    t.put("format", format);
                    t.put("dataset", datasetByRef.get(name));   // owning dataset id, or null
                    tables.add(t);
                }
            } catch (IOException e) {
                throw new ApiException(503, "could not list data directory: " + e.getMessage());
            }
        }

        Map<String, Object> stores = new LinkedHashMap<>();
        stores.put("id", STORES_GROUP);
        stores.put("label", "Data Stores");
        stores.put("kind", "parquet");
        stores.put("tables", tables);

        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(stores);
        groups.addAll(operationalGroups(api));
        return Map.of("groups", groups);
    }

    /** The live DB-backed operational stores as catalog groups (empty on the default in-memory/file backends). */
    private static List<Map<String, Object>> operationalGroups(ApiContext api) {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (BrowsableStore b : api.service().browsableStores()) {
            List<Map<String, Object>> tables = new ArrayList<>();
            for (String t : b.browseTables()) tables.add(Map.of("name", t));
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", "ops:" + b.browseId());
            g.put("label", "Operational · " + b.browseLabel());
            g.put("kind", "operational");
            g.put("engine", b.browseEngine());
            g.put("live", true);
            g.put("tables", tables);
            groups.add(g);
        }
        return groups;
    }

    /** Map each dataset component's {@code physicalRef} → its id, so the catalog can name a store's owner. */
    private static Map<String, String> datasetByRef(Path writeRoot) {
        Map<String, String> byRef = new LinkedHashMap<>();
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        for (ComponentRegistry.Component c : store.list("dataset")) {
            Object ref = c.content().get("physicalRef");
            if (ref != null) byRef.putIfAbsent(String.valueOf(ref), c.name());
        }
        return byRef;
    }

    // ── GET /db/table?group=&name=&limit=&offset=&sort=field:dir ───────────────────

    private Object table(ApiContext api, HttpExchange ex) throws IOException {
        WriteGates.requireWriteRoot(api, "table browser");
        String group = orDefault(ApiContext.query(ex, "group"), STORES_GROUP);
        String name = ApiContext.query(ex, "name");
        if (name == null || name.isBlank()) throw new ApiException(400, "missing 'name'");
        int limit = clampLimit(ApiContext.parseIntOr(ApiContext.query(ex, "limit"), DEFAULT_LIMIT));
        int offset = Math.max(0, ApiContext.parseIntOr(ApiContext.query(ex, "offset"), 0));
        List<QueryExecutor.Sort> sort = parseSort(ApiContext.query(ex, "sort"));

        if (group.startsWith("ops:"))
            return browseOperational(api, group, name, null, limit, offset);
        if (!STORES_GROUP.equals(group))
            throw new ApiException(404, "unknown group '" + group + "'");
        return browseStore(api, name, null, limit, offset, sort);
    }

    // ── POST /db/query {group,table,sql,limit,offset} ──────────────────────────────

    private Object query(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "table browser");
        String group = orDefault(ApiContext.str(body, "group"), STORES_GROUP);
        String tableName = ApiContext.str(body, "table");
        String sql = ApiContext.str(body, "sql");
        if (sql == null) throw new ApiException(422, "missing 'sql'");

        List<Finding> findings = SqlGuard.check(sql);
        if (!findings.isEmpty())
            return ApiContext.respondJson(ex, 422, Map.of(
                    "error", "SQL failed the read-only safety check", "findings", findings));

        int limit = clampLimit(intOr(body.get("limit"), DEFAULT_LIMIT));
        int offset = Math.max(0, intOr(body.get("offset"), 0));
        if (group.startsWith("ops:"))
            return browseOperational(api, group, tableName, sql, limit, offset);
        if (!STORES_GROUP.equals(group))
            throw new ApiException(404, "unknown group '" + group + "'");
        if (tableName == null) throw new ApiException(422, "missing 'table' (the store the SQL reads from)");
        return browseStore(api, tableName, sql, limit, offset, List.of());
    }

    // ── shared Parquet/CSV store read ──────────────────────────────────────────────

    /**
     * Read one business-data store as a relation. Builds the trusted {@code read_parquet}/{@code read_csv}
     * relation ({@link SqlViews}), registers it as a view named after the store, and runs either a
     * generated {@code SELECT *} (browse) or the caller's already-{@code SqlGuard}-checked SQL (ad-hoc)
     * over it in the sandbox.
     */
    private Object browseStore(ApiContext api, String storeName, String userSql,
                               int limit, int offset, List<QueryExecutor.Sort> sort) {
        Path dataRoot = api.dataRoot();
        if (dataRoot == null) throw new ApiException(404, "no data directory for this space");
        Path root = dataRoot.normalize();
        Path storeDir = root.resolve(storeName).normalize();
        if (!storeDir.startsWith(root)) throw new ApiException(403, "store path escapes the data root");
        if (!Files.isDirectory(storeDir)) throw new ApiException(404, "no store '" + storeName + "'");

        String format = detectFormat(storeDir);
        if (format == null) throw new ApiException(404, "store '" + storeName + "' has no parquet/csv data");

        String glob = storeDir.toString().replace('\\', '/') + "/**/*." + SqlViews.ext(format);
        // QueryExecutor registers this as `CREATE VIEW <store> AS <relationSql>`, so it must be a full
        // SELECT — the same wrap DatasetRelation / SourceStoreReader apply around SqlViews.reader(...).
        String relationSql = "SELECT * FROM " + SqlViews.reader(format, glob, true);
        String sql = userSql != null ? userSql : "SELECT * FROM " + q(storeName);

        QueryExecutor.Request req = new QueryExecutor.Request(storeName, relationSql, sql,
                limit, offset, List.of(), sort);
        try {
            return response(QueryExecutor.run(req));
        } catch (IllegalArgumentException bad) {          // unsafe projection/sort identifier
            throw new ApiException(422, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, "query failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ApiException(503, "query sandbox unavailable: " + e.getMessage());
        }
    }

    // ── operational (control-plane DB) table read via the live-connection BrowsableStore seam ──

    /**
     * Read one operational store's table (or run its {@code SqlGuard}-checked ad-hoc SQL) through the
     * store's live connection — {@link BrowsableStore} runs it {@code synchronized} on the store, so it
     * never races the store's own writes and never opens a second (lock-conflicting) connection.
     */
    private Object browseOperational(ApiContext api, String group, String table, String userSql,
                                     int limit, int offset) {
        String id = group.substring("ops:".length());
        BrowsableStore store = null;
        for (BrowsableStore b : api.service().browsableStores())
            if (b.browseId().equals(id)) { store = b; break; }
        if (store == null)
            throw new ApiException(404, "no live operational store '" + id + "' (its backend is not DB-backed)");
        if (userSql == null && (table == null || table.isBlank()))
            throw new ApiException(400, "missing 'name'");
        long t0 = System.nanoTime();
        try {
            BrowsableStore.Page page = userSql != null
                    ? store.browseQuery(userSql, limit, offset)
                    : store.browseTable(table, limit, offset);
            return responseFromPage(page, (System.nanoTime() - t0) / 1_000_000);
        } catch (IllegalArgumentException bad) {          // unknown table for this store
            throw new ApiException(404, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, "query failed: " + e.getMessage());
        }
    }

    private static Object responseFromPage(BrowsableStore.Page p, long elapsedMs) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (BrowsableStore.Column c : p.columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name());
            col.put("type", c.type());
            col.put("role", null);
            col.put("cardinality", null);
            columns.add(col);
        }
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", p.rows().size());
        statistics.put("elapsedMs", elapsedMs);
        statistics.put("truncated", p.truncated());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", columns);
        data.put("rows", p.rows());
        data.put("statistics", statistics);
        return data;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** The at-rest format of a store dir: {@code "PARQUET"} / {@code "CSV"}, or {@code null} if neither. */
    private static String detectFormat(Path storeDir) {
        try (Stream<Path> s = Files.walk(storeDir, 6)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".parquet") ? "PARQUET" : n.endsWith(".csv") ? "CSV" : null;
                    })
                    .filter(f -> f != null)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Object response(QueryExecutor.Result r) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ResultSetDescriptor.Column c : r.columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name());
            col.put("type", c.type());
            col.put("role", c.role());
            col.put("cardinality", c.cardinality());
            columns.add(col);
        }
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", r.rowCount());
        statistics.put("elapsedMs", r.elapsedMs());
        statistics.put("truncated", r.truncated());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", columns);
        data.put("rows", r.rows());
        data.put("statistics", statistics);
        return data;
    }

    private static List<QueryExecutor.Sort> parseSort(String s) {
        if (s == null || s.isBlank()) return List.of();
        int c = s.lastIndexOf(':');
        String field = c > 0 ? s.substring(0, c) : s;
        boolean desc = c > 0 && "desc".equalsIgnoreCase(s.substring(c + 1));
        return List.of(new QueryExecutor.Sort(field.trim(), desc));
    }

    private static int clampLimit(int l) {
        return Math.max(1, Math.min(MAX_LIMIT, l));
    }

    private static int intOr(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return def;
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
