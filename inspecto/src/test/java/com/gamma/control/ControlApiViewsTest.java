package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.SourceService;
import com.gamma.sql.SqlViews;
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
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code sink.view} consumer (T32 Phase C follow-up) over real HTTP: discover view definitions
 * (<code>GET /views</code>, <code>/views/{name}</code>) and query a view's {@code derived_sql} for bounded
 * rows (<code>/views/{name}/data</code>). Seeds a real Parquet store + a {@link ViewDefinition} under
 * {@code <write-root>/views} the way a flow job would.
 */
class ControlApiViewsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        SourceService svc = new SourceService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    /** Write a 3-row Hive-partitioned Parquet store at {@code <dataDir>/orders} and register a view over it. */
    private void seedViews(Path writeRoot, Path dataDir) throws Exception {
        Path partition = dataDir.resolve("orders").resolve("dt=2026");
        Files.createDirectories(partition);
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("views_test_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES (1,'alice'),(2,'bob'),(3,'carol')) t(id,name)) TO '"
                    + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        String glob = dataDir.toString().replace("\\", "/") + "/orders/**/*.parquet";
        String derivedSql = "SELECT * FROM " + SqlViews.reader("PARQUET", glob, true);

        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        views.write(new ViewDefinition("orders_view", "orders_flow", List.of("orders"),
                derivedSql, Instant.now().toString()));
        // a view that could not be reduced to a single SELECT — derived_sql is null
        views.write(new ViewDefinition("complex_view", "complex_flow", List.of("a", "b"),
                null, Instant.now().toString()));
    }

    @Test
    void listsAndReadsAView(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        Path dataDir = dir.resolve("data");
        try (Ctx c = open(dir, wr)) {
            seedViews(wr, dataDir);

            // list — both views, with the derived-sql flag
            JsonNode list = json(send(c.port, "GET", "/views", null));
            assertEquals(2, list.size(), list.toString());
            JsonNode orders = byStore(list, "orders_view");
            assertTrue(orders.get("has_derived_sql").asBoolean());
            assertEquals("orders_flow", orders.get("flow").asText());
            assertFalse(byStore(list, "complex_view").get("has_derived_sql").asBoolean());

            // definition — carries the derived_sql + lineage
            JsonNode def = json(send(c.port, "GET", "/views/orders_view", null));
            assertTrue(def.get("derived_sql").asText().contains("read_parquet"));
            assertEquals("orders", def.get("source_store").get(0).asText());

            // data — runs the derived_sql and returns the rows
            HttpResponse<String> dataResp = send(c.port, "GET", "/views/orders_view/data", null);
            assertEquals(200, dataResp.statusCode(), dataResp.body());
            JsonNode data = json(dataResp);
            assertEquals(3, data.get("rowCount").asInt(), data.toString());
            assertFalse(data.get("capped").asBoolean());
            // id + name + the hive partition column dt (hive_partitioning=true)
            assertTrue(columnNames(data).containsAll(List.of("id", "name", "dt")), data.toString());
            assertEquals(3, data.get("rows").size());
        }
    }

    @Test
    void limitCapsTheResult(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            seedViews(wr, dir.resolve("data"));
            JsonNode data = json(send(c.port, "GET", "/views/orders_view/data?limit=2", null));
            assertEquals(2, data.get("rowCount").asInt(), data.toString());
            assertTrue(data.get("capped").asBoolean());
        }
    }

    @Test
    void missingViewIs404AndViewWithoutDerivedSqlIs409(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            seedViews(wr, dir.resolve("data"));
            assertEquals(404, send(c.port, "GET", "/views/ghost", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/views/ghost/data", null).statusCode());
            // a view with no derived_sql cannot be queried directly → 409 (re-run its flow)
            assertEquals(409, send(c.port, "GET", "/views/complex_view/data", null).statusCode());
        }
    }

    @Test
    void emptyWithoutAWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            assertEquals(List.of(), JSON.readValue(send(c.port, "GET", "/views", null).body(), List.class));
            assertEquals(404, send(c.port, "GET", "/views/orders_view", null).statusCode());
        }
    }

    private static List<String> columnNames(JsonNode data) {
        List<String> cols = new java.util.ArrayList<>();
        for (JsonNode c : data.get("columns")) cols.add(c.asText());
        return cols;
    }

    private static JsonNode byStore(JsonNode list, String store) {
        for (JsonNode v : list) if (store.equals(v.get("store").asText())) return v;
        throw new AssertionError("no view '" + store + "' in " + list);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }
}
