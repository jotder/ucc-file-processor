package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SourceService;
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
 * Real-HTTP + real-DuckDB tests for the reconciliation routes (DAT-7, design
 * {@code docs/superpower/reconciliation-board-design.md}): {@code POST /recon/columns} inventories the
 * sides for the unified column list, {@code POST /recon/run} returns the Board's grain rows + exact
 * totals + Break summary (saved {@code reconciliation} component or inline draft config), and
 * {@code POST /recon/breaks} returns the paged Break sets scoped by a dimension path. Also proves the
 * {@code reconciliation} component kind is writable through the generic {@code /components} CRUD.
 * Fails closed: 503 (write root disabled), 404 (unknown reconciliation / dataset), 422 (unusable config).
 */
class ControlApiReconTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /**
     * Boot a one-space container with two Parquet stores mirroring the design doc's example:
     * EU/voice matched-equal (A has 2×100, B has 1×200), EU/data matched outside the 0.5% tolerance
     * (118 vs 114), US/voice matched-equal, MEA/voice only in A, APAC/sms only in B.
     */
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
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));

        Path dataDir = base.resolve("data");
        seedStore(dataDir, "orders_a", "VALUES ('EU','voice',100.0),('EU','voice',100.0),"
                + "('EU','data',118.0),('US','voice',50.0),('MEA','voice',10.0)");
        seedStore(dataDir, "orders_b", "VALUES ('EU','voice',200.0),('EU','data',114.0),"
                + "('US','voice',50.0),('APAC','sms',7.0)");

        ComponentStore store = new ComponentStore(config.resolve("registry"));
        store.write("dataset", "a_ds", Map.of("physicalRef", "orders_a"));
        store.write("dataset", "b_ds", Map.of("physicalRef", "orders_b"));
        store.write("reconciliation", "orders_recon", reconConfig());
    }

    private static Map<String, Object> reconConfig() {
        return Map.of(
                "datasets", List.of("a_ds", "b_ds"),
                "keyColumns", List.of("region", "product"),
                "compareColumns", List.of(Map.of("column", "amount", "toleranceType", "percent", "tolerance", 0.5)));
    }

    private static void seedStore(Path dataDir, String name, String values) throws Exception {
        Path partition = dataDir.resolve(name).resolve("dt=2026");
        Files.createDirectories(partition);
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("recon_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (" + values + ") t(region, product, amount)) TO '"
                    + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private HttpResponse<String> postJson(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).get("data");
    }

    // ── POST /recon/run ────────────────────────────────────────────────────────────

    @Test
    void runBySavedIdReturnsGrainTotalsAndSummary(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = postJson(c.port, "/api/v1/spaces/s1/recon/run", "{\"id\":\"orders_recon\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = data(r);

            assertEquals(5, d.get("statistics").get("rowCount").asInt());
            assertFalse(d.get("statistics").get("truncated").asBoolean());
            assertEquals("region", d.get("keyColumns").get(0).asText());
            assertEquals("amount", d.get("measures").get(0).asText());

            // the EU/data grain row: 118 vs 114, both sides present
            JsonNode euData = null;
            for (JsonNode row : d.get("rows"))
                if ("EU".equals(row.get("key").get("region").asText())
                        && "data".equals(row.get("key").get("product").asText())) euData = row;
            assertNotNull(euData, d.get("rows").toString());
            assertEquals(118.0, euData.get("a").get("amount").asDouble());
            assertEquals(114.0, euData.get("b").get("amount").asDouble());
            assertTrue(euData.get("inA").asBoolean() && euData.get("inB").asBoolean());

            // exact totals + summary
            assertEquals(378.0, d.get("totals").get("a").get("amount").asDouble());
            assertEquals(371.0, d.get("totals").get("b").get("amount").asDouble());
            assertEquals(5, d.get("totals").get("a").get("__records").asInt());
            assertEquals(3, d.get("summary").get("matchedKeys").asInt());
            assertEquals(1, d.get("summary").get("byType").get("missing_right").asInt());
            assertEquals(1, d.get("summary").get("byType").get("missing_left").asInt());
            assertEquals(1, d.get("summary").get("byType").get("value_break").asInt());
        }
    }

    @Test
    void inlineDraftConfigRunsWithoutASavedComponent(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = postJson(c.port, "/api/v1/spaces/s1/recon/run",
                    "{\"config\":{\"datasets\":[\"a_ds\",\"b_ds\"],\"keyColumns\":[\"region\"],"
                            + "\"compareColumns\":[{\"column\":\"amount\"}]}}");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(4, data(r).get("statistics").get("rowCount").asInt(), "4 regions at region grain");
        }
    }

    // ── POST /recon/breaks ─────────────────────────────────────────────────────────

    @Test
    void breaksReturnsTheThreeSetsAndScopesByPath(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode all = data(postJson(c.port, "/api/v1/spaces/s1/recon/breaks", "{\"id\":\"orders_recon\"}"));
            assertEquals(1, all.get("missing_right").get("rowCount").asInt());
            assertEquals("MEA", all.get("missing_right").get("rows").get(0).get("key").get("region").asText());
            assertEquals(10.0, all.get("missing_right").get("rows").get(0).get("a").get("amount").asDouble());
            assertNull(all.get("missing_right").get("rows").get(0).get("b"), "missing_right carries side A only");
            assertEquals("APAC", all.get("missing_left").get("rows").get(0).get("key").get("region").asText());
            JsonNode vb = all.get("value_break").get("rows").get(0);
            assertEquals(118.0, vb.get("a").get("amount").asDouble());
            assertEquals(114.0, vb.get("b").get("amount").asDouble());

            // scoped to the Board path region=EU → no missing_right, the one value_break
            JsonNode eu = data(postJson(c.port, "/api/v1/spaces/s1/recon/breaks",
                    "{\"id\":\"orders_recon\",\"path\":{\"region\":\"EU\"}}"));
            assertEquals(0, eu.get("missing_right").get("rowCount").asInt());
            assertEquals(1, eu.get("value_break").get("rowCount").asInt());

            // type filter returns only that set
            JsonNode one = data(postJson(c.port, "/api/v1/spaces/s1/recon/breaks",
                    "{\"id\":\"orders_recon\",\"type\":\"missing_left\"}"));
            assertNotNull(one.get("missing_left"));
            assertNull(one.get("missing_right"));
        }
    }

    // ── POST /recon/columns ────────────────────────────────────────────────────────

    @Test
    void columnsInventoriesBothSidesWithMatches(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = postJson(c.port, "/api/v1/spaces/s1/recon/columns",
                    "{\"datasets\":[\"a_ds\",\"b_ds\"]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = data(r);
            assertEquals(2, d.get("datasets").size());
            // both stores share region/product/amount plus the hive-partition column dt → four auto-matches
            assertEquals(4, d.get("matches").size(), d.get("matches").toString());
            JsonNode amount = null;
            for (JsonNode m : d.get("matches")) if ("amount".equals(m.get("name").asText())) amount = m;
            assertNotNull(amount);
            assertTrue(amount.get("numeric").asBoolean());
            assertEquals("amount", amount.get("columns").get("b_ds").asText());
        }
    }

    // ── component kind + gates ─────────────────────────────────────────────────────

    @Test
    void reconciliationComponentKindIsWritableOverHttp(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> created = postJson(c.port, "/api/v1/spaces/s1/components/reconciliation",
                    "{\"id\":\"via_http\",\"datasets\":[\"a_ds\",\"b_ds\"],\"keyColumns\":[\"region\"],"
                            + "\"compareColumns\":[{\"column\":\"amount\"}]}");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("reconciliation/via_http", data(created).get("ref").asText());

            // and the saved component runs
            HttpResponse<String> run = postJson(c.port, "/api/v1/spaces/s1/recon/run", "{\"id\":\"via_http\"}");
            assertEquals(200, run.statusCode(), run.body());
            assertEquals(4, data(run).get("statistics").get("rowCount").asInt());
        }
    }

    /** P3: reconciliations travel in Metadata Bundles — config only, run state stripped at export. */
    @Test
    void bundleExportStripsRunStateAndRoundTrips(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // give the stored recon some run state that must NOT travel
            HttpResponse<String> run = postJson(c.port, "/api/v1/spaces/s1/recon/run", "{\"id\":\"orders_recon\"}");
            assertEquals(200, run.statusCode(), run.body());

            HttpResponse<String> exported = postJson(c.port, "/api/v1/spaces/s1/bundle/export",
                    "{\"items\":[{\"kind\":\"reconciliation\",\"id\":\"orders_recon\"},"
                            + "{\"kind\":\"dataset\",\"id\":\"a_ds\"},{\"kind\":\"dataset\",\"id\":\"b_ds\"}]}");
            assertEquals(200, exported.statusCode(), exported.body());
            JsonNode bundle = data(exported).get("bundle");
            JsonNode recon = null;
            for (JsonNode item : bundle.get("items"))
                if ("reconciliation".equals(item.get("kind").asText())) recon = item;
            assertNotNull(recon, "reconciliation item exported");
            assertNull(recon.get("content").get("breaks"), "run state stripped");
            assertNull(recon.get("content").get("lastRunAt"), "run state stripped");
            assertEquals("a_ds", recon.get("content").get("datasets").get(0).asText());

            // import the exported bundle back under a new id → lands in the registry and runs
            String reimport = bundle.toString().replace("orders_recon", "orders_recon_copy");
            HttpResponse<String> imported = postJson(c.port, "/api/v1/spaces/s1/bundle/import", reimport);
            assertEquals(200, imported.statusCode(), imported.body());
            assertEquals(200, postJson(c.port, "/api/v1/spaces/s1/recon/run",
                    "{\"id\":\"orders_recon_copy\"}").statusCode());
        }
    }

    @Test
    void failsClosed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // unknown reconciliation / dataset → 404
            assertEquals(404, postJson(c.port, "/api/v1/spaces/s1/recon/run", "{\"id\":\"ghost\"}").statusCode());
            assertEquals(404, postJson(c.port, "/api/v1/spaces/s1/recon/run",
                    "{\"config\":{\"datasets\":[\"a_ds\",\"ghost_ds\"],\"keyColumns\":[\"region\"],"
                            + "\"compareColumns\":[{\"column\":\"amount\"}]}}").statusCode());
            // neither id nor config, wrong dataset count, bad agg, unsafe key, guarded filter, bad path → 422
            assertEquals(422, postJson(c.port, "/api/v1/spaces/s1/recon/run", "{}").statusCode());
            assertEquals(422, postJson(c.port, "/api/v1/spaces/s1/recon/run",
                    "{\"config\":{\"datasets\":[\"a_ds\"],\"keyColumns\":[\"region\"],"
                            + "\"compareColumns\":[{\"column\":\"amount\"}]}}").statusCode());
            assertEquals(422, postJson(c.port, "/api/v1/spaces/s1/recon/run",
                    "{\"config\":{\"datasets\":[\"a_ds\",\"b_ds\"],\"keyColumns\":[\"region\"],"
                            + "\"compareColumns\":[{\"column\":\"amount\",\"agg\":\"avg\"}]}}").statusCode());
            assertEquals(422, postJson(c.port, "/api/v1/spaces/s1/recon/run",
                    "{\"config\":{\"datasets\":[\"a_ds\",\"b_ds\"],\"keyColumns\":[\"region;drop\"],"
                            + "\"compareColumns\":[{\"column\":\"amount\"}]}}").statusCode());
            assertEquals(422, postJson(c.port, "/api/v1/spaces/s1/recon/run",
                    "{\"config\":{\"datasets\":[\"a_ds\",\"b_ds\"],\"keyColumns\":[\"region\"],"
                            + "\"compareColumns\":[{\"column\":\"amount\"}],"
                            + "\"filters\":{\"a_ds\":\"select 1\"}}}").statusCode());
            assertEquals(422, postJson(c.port, "/api/v1/spaces/s1/recon/breaks",
                    "{\"id\":\"orders_recon\",\"path\":{\"amount\":\"1\"}}").statusCode());
            // /recon/columns with no datasets → 422; unknown dataset → 404
            assertEquals(422, postJson(c.port, "/api/v1/spaces/s1/recon/columns", "{}").statusCode());
            assertEquals(404, postJson(c.port, "/api/v1/spaces/s1/recon/columns",
                    "{\"datasets\":[\"ghost_ds\"]}").statusCode());
        }
    }

    @Test
    void writeRootDisabledIs503(@TempDir Path cfg) throws Exception {
        // Legacy single-space harness with no -Dassist.write.root → writeRoot() is null → 503.
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        String prior = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        SourceService svc = new SourceService(List.of(pipe), 3600, 1);
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                assertEquals(503, postJson(api.port(), "/api/v1/recon/run", "{\"id\":\"x\"}").statusCode());
            } finally {
                api.close();
            }
        } finally {
            svc.close();
            if (prior != null) System.setProperty("assist.write.root", prior);
        }
    }
}
