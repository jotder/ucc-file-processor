package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceManager;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP + real-DuckDB tests for the raw table browser (design {@code docs/superpower/db-browser-design.md}):
 * {@code GET /db/catalog} lists a space's business-data stores, {@code GET /db/table} returns typed columns +
 * paginated/sorted rows, and {@code POST /db/query} runs ad-hoc read-only SQL over one store — reusing the
 * {@link com.gamma.sql.SqlGuard} + sandboxed {@link com.gamma.query.QueryExecutor} path. Uses the multi-space
 * ({@code DirSpaceRoot}) seam so {@code dataRoot()} is a real per-space temp directory. Fails closed:
 * 503 (write root disabled), 404 (unknown store / group), 422 (SqlGuard violation).
 */
class ControlApiDbBrowserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /** Boot a one-space container whose {@code data/} holds a 3-row Hive-partitioned Parquet store "orders". */
    private Ctx open(Path root) throws Exception {
        seedSpace(root, "s1");
        SpaceManager spaces = SpaceManager.discover(root);
        assertEquals(1, spaces.size(), "space booted");
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private void seedSpace(Path root, String id) throws Exception {
        Path base = root.resolve(id);
        Path config = base.resolve("config");
        Files.createDirectories(config.resolve("inbox"));   // empty at boot
        Files.createDirectories(base.resolve("duckdb"));    // where DB-backed operational stores open their files
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));   // dir-scan discovers *_pipeline.toon

        // A real 3-row Parquet store under the space data dir (what dataRoot() resolves to).
        Path dataDir = root.resolve(id).resolve("data");
        Path partition = dataDir.resolve("orders").resolve("dt=2026");
        Files.createDirectories(partition);
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("dbbrowser_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES (1,'alice'),(2,'bob'),(3,'carol')) t(id,name)) TO '"
                    + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        // A dataset component that owns the store, so the catalog can name the owner.
        new ComponentStore(config.resolve("registry")).write("dataset", "orders_ds", Map.of("physicalRef", "orders"));
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void catalogListsBusinessStores(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = get(c.port, "/api/v1/spaces/s1/db/catalog");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode groups = JSON.readTree(r.body()).get("data").get("groups");
            assertEquals(1, groups.size());
            JsonNode stores = groups.get(0);
            assertEquals("stores", stores.get("id").asText());
            assertEquals("parquet", stores.get("kind").asText());
            JsonNode tables = stores.get("tables");
            JsonNode orders = null;
            for (JsonNode t : tables) if ("orders".equals(t.get("name").asText())) orders = t;
            assertNotNull(orders, "the seeded 'orders' store is listed: " + tables);
            assertEquals("PARQUET", orders.get("format").asText());
            assertEquals("orders_ds", orders.get("dataset").asText(), "the owning dataset is named");
        }
    }

    @Test
    void tableReturnsTypedColumnsAndPaginatesAndSorts(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // page of 2 of 3 rows → truncated
            JsonNode data = JSON.readTree(get(c.port, "/api/v1/spaces/s1/db/table?name=orders&limit=2").body()).get("data");
            assertEquals(2, data.get("rows").size());
            assertTrue(data.get("statistics").get("truncated").asBoolean(), "3 rows, limit 2 → truncated");
            List<String> cols = new java.util.ArrayList<>();
            for (JsonNode col : data.get("columns")) cols.add(col.get("name").asText());
            assertTrue(cols.contains("id") && cols.contains("name"), cols.toString());

            // sort id desc → first row is carol (id 3)
            JsonNode sorted = JSON.readTree(
                    get(c.port, "/api/v1/spaces/s1/db/table?name=orders&sort=id:desc").body()).get("data");
            assertEquals(3, sorted.get("rows").size());
            assertEquals("carol", sorted.get("rows").get(0).get("name").asText());
        }
    }

    @Test
    void adHocReadOnlySqlRunsOverAStore(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = postJson(c.port, "/api/v1/spaces/s1/db/query",
                    "{\"table\":\"orders\",\"sql\":\"SELECT name FROM \\\"orders\\\" WHERE id = 2\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = JSON.readTree(r.body()).get("data").get("rows");
            assertEquals(1, rows.size());
            assertEquals("bob", rows.get(0).get("name").asText());
        }
    }

    /**
     * A store named after a registered pipeline browses the pipeline's mapped output ({@code dirs.database}),
     * not the raw pre-mapping {@code backup/} copies colocated under {@code data/<name>} — the whole-tree
     * glob used to lock onto whichever file the directory walk hit first, hiding mapped-only columns.
     */
    @Test
    void pipelineStoreBrowsesMappedDatabaseOutput(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // the raw backup copy the old whole-tree glob could lock onto (CSV, pre-mapping columns)
            Path backup = root.resolve("s1").resolve("data").resolve("test_etl").resolve("backup");
            Files.createDirectories(backup);
            Files.writeString(backup.resolve("RAW_20260701.csv"), "id,name\n1,alice\n");

            // the pipeline's mapped output (TestConfigs sets dirs.database = <config>/db) with a mapped-only column
            Path dbDir = root.resolve("s1").resolve("config").resolve("db").resolve("dt=2026");
            Files.createDirectories(dbDir);
            String parquet = dbDir.resolve("out.parquet").toString().replace("\\", "/");
            DuckDbUtil.loadDriver();
            File db = DuckDbUtil.tempDbFile("dbbrowser_pipe_");
            try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT 1 AS id, 'alice' AS name, 42.0 AS gross) TO '" + parquet + "' (FORMAT PARQUET)");
            } finally {
                DuckDbUtil.deleteTempDb(db);
            }

            JsonNode data = JSON.readTree(get(c.port, "/api/v1/spaces/s1/db/table?name=test_etl").body()).get("data");
            List<String> cols = new java.util.ArrayList<>();
            for (JsonNode col : data.get("columns")) cols.add(col.get("name").asText());
            assertTrue(cols.contains("gross"), "mapped output columns are served, not the raw backup CSV: " + cols);
            assertEquals(1, data.get("rows").size());
        }
    }

    @Test
    void failsClosed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // unknown store → 404
            assertEquals(404, get(c.port, "/api/v1/spaces/s1/db/table?name=ghost").statusCode());
            // unknown group (operational not yet browsable) → 404
            assertEquals(404, get(c.port, "/api/v1/spaces/s1/db/table?group=ops:objects&name=x").statusCode());
            // file-reading SQL is rejected by the guard → 422
            HttpResponse<String> guarded = postJson(c.port, "/api/v1/spaces/s1/db/query",
                    "{\"table\":\"orders\",\"sql\":\"SELECT * FROM read_csv('/etc/passwd')\"}");
            assertEquals(422, guarded.statusCode(), guarded.body());
            // a mutating statement is rejected by the guard → 422
            assertEquals(422, postJson(c.port, "/api/v1/spaces/s1/db/query",
                    "{\"table\":\"orders\",\"sql\":\"DROP TABLE orders\"}").statusCode());
        }
    }

    @Test
    void writeRootDisabledIs503(@TempDir Path cfg) throws Exception {
        // Legacy single-space harness with no -Dassist.write.root → writeRoot() is null → 503.
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        String prior = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                assertEquals(503, get(api.port(), "/api/v1/db/catalog").statusCode());
            } finally {
                api.close();
            }
        } finally {
            svc.close();
            if (prior != null) System.setProperty("assist.write.root", prior);
        }
    }

    /** Phase 2: with objects on the DB backend, the operational tables browse through the live connection. */
    @Test
    void operationalTablesBrowsableWhenDbBacked(@TempDir Path root) throws Exception {
        String prior = System.getProperty("objects.backend");
        System.setProperty("objects.backend", "db");   // makes DbObjectStore live (a BrowsableStore)
        try (Ctx c = open(root)) {
            // seed one row via the API (POST /objects defaults to an INCIDENT)
            HttpResponse<String> created = postJson(c.port, "/api/v1/spaces/s1/objects",
                    "{\"title\":\"DB browser probe\",\"type\":\"INCIDENT\"}");
            assertTrue(created.statusCode() < 300, created.body());

            // catalog now carries the operational objects group alongside the parquet stores
            JsonNode groups = JSON.readTree(get(c.port, "/api/v1/spaces/s1/db/catalog").body()).get("data").get("groups");
            JsonNode ops = null;
            for (JsonNode g : groups) if ("ops:objects".equals(g.get("id").asText())) ops = g;
            assertNotNull(ops, "operational objects group present: " + groups);
            assertEquals("operational", ops.get("kind").asText());
            boolean hasTable = false;
            for (JsonNode t : ops.get("tables")) if ("inspecto_ops_objects".equals(t.get("name").asText())) hasTable = true;
            assertTrue(hasTable, "inspecto_ops_objects listed: " + ops.get("tables"));

            // browse the table through the live connection
            JsonNode data = JSON.readTree(get(c.port,
                    "/api/v1/spaces/s1/db/table?group=ops:objects&name=inspecto_ops_objects").body()).get("data");
            assertEquals(1, data.get("rows").size());
            assertEquals("DB browser probe", data.get("rows").get(0).get("title").asText());

            // ad-hoc read-only SQL over the live connection
            JsonNode q = JSON.readTree(postJson(c.port, "/api/v1/spaces/s1/db/query",
                    "{\"group\":\"ops:objects\",\"sql\":\"SELECT title FROM inspecto_ops_objects\"}").body()).get("data");
            assertEquals("DB browser probe", q.get("rows").get(0).get("title").asText());

            // unknown table for the group → 404; a mutating statement → 422
            assertEquals(404, get(c.port,
                    "/api/v1/spaces/s1/db/table?group=ops:objects&name=inspecto_bogus").statusCode());
            assertEquals(422, postJson(c.port, "/api/v1/spaces/s1/db/query",
                    "{\"group\":\"ops:objects\",\"sql\":\"DELETE FROM inspecto_ops_objects\"}").statusCode());
        } finally {
            if (prior != null) System.setProperty("objects.backend", prior);
            else System.clearProperty("objects.backend");
        }
    }
}
