package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.service.SourceService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP + real-DuckDB tests for the ING-6 Expectation engine ({@code /expectations*}): authored CRUD
 * over the {@code expectation} component store, the four data-quality kinds counting violations in a
 * target's at-rest Parquet, the fail-closed gates, and the failure consequence chain (a deduped Incident
 * for {@code expectation:<name>}). The legacy space's data root is {@code ./database}, so each test seeds
 * a uniquely-named Parquet store under it and cleans up.
 */
class ControlApiExpectationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            SourceService svc = new SourceService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    // ── CRUD + gates ──────────────────────────────────────────────────────────────

    @Test
    void crudRoundTripAndGates(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String body = "{\"name\":\"e1\",\"targetType\":\"pipeline\",\"target\":\"orders\","
                    + "\"column\":\"email\",\"kind\":\"non_null\",\"severity\":\"MAJOR\"}";
            JsonNode created = json(send(c.port, "POST", "/expectations", body));
            assertEquals("e1", created.get("name").asText());
            assertEquals("non_null", created.get("kind").asText());
            assertTrue(created.get("lastResult").isNull());

            // list contains it
            JsonNode list = json(send(c.port, "GET", "/expectations", null));
            assertTrue(list.isArray() && list.size() == 1 && "e1".equals(list.get(0).get("name").asText()));

            // duplicate create → 409
            assertEquals(409, send(c.port, "POST", "/expectations", body).statusCode());
            // bad body (missing target) → 422
            assertEquals(422, send(c.port, "POST", "/expectations",
                    "{\"name\":\"bad\",\"column\":\"x\",\"kind\":\"non_null\"}").statusCode());
            // unknown kind → 422
            assertEquals(422, send(c.port, "POST", "/expectations",
                    "{\"name\":\"bad2\",\"target\":\"o\",\"column\":\"x\",\"kind\":\"bogus\"}").statusCode());

            // update
            JsonNode updated = json(send(c.port, "PUT", "/expectations/e1",
                    "{\"name\":\"e1\",\"target\":\"orders\",\"column\":\"email\",\"kind\":\"non_null\","
                            + "\"description\":\"emails must be present\"}"));
            assertEquals("emails must be present", updated.get("description").asText());
            // update unknown → 404
            assertEquals(404, send(c.port, "PUT", "/expectations/nope",
                    "{\"target\":\"o\",\"column\":\"x\",\"kind\":\"non_null\"}").statusCode());

            // delete + delete-unknown
            assertEquals("e1", json(send(c.port, "DELETE", "/expectations/e1", null)).get("deleted").asText());
            assertEquals(404, send(c.port, "DELETE", "/expectations/e1", null).statusCode());
        }
    }

    // ── evaluation: the four kinds ─────────────────────────────────────────────────

    @Test
    void evaluatesAllFourKindsAgainstRealParquet(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        String target = "orders_" + System.nanoTime();
        String ref = "countries_" + System.nanoTime();
        seedParquet(target, "SELECT * FROM (VALUES "
                + "(1,'a@x.com',10.0,'US'),(2,NULL,20.0,'US'),(3,'bad',999.0,'ZZ'),(4,'c@x.com',50.0,'FR')"
                + ") t(id,email,amount,cc)");
        seedParquet(ref, "SELECT * FROM (VALUES ('US'),('FR')) t(code)");
        try (Ctx c = open(cfg, wr)) {
            // non_null on email → row 2 → 1 violation, FAILED
            assertViolations(c, mk("nn", target, "email", "non_null", null), 1, "FAILED");
            // non_null on id → 0 → PASSED
            assertViolations(c, mk("nn_id", target, "id", "non_null", null), 0, "PASSED");
            // range on amount [0,100] → row 3 (999) → 1
            assertViolations(c, mk("rng", target, "amount", "range", "\"min\":0,\"max\":100"), 1, "FAILED");
            // regex on email → row 3 ('bad'); NULLs excluded → 1
            assertViolations(c, mk("rx", target, "email", "regex", "\"pattern\":\"^[^@]+@[^@]+$\""), 1, "FAILED");
            // referential cc → countries.code → 'ZZ' dangling → 1
            assertViolations(c, mk("ref", target, "cc", "referential",
                    "\"refDataset\":\"" + ref + "\",\"refColumn\":\"code\""), 1, "FAILED");
        } finally {
            cleanup(target);
            cleanup(ref);
        }
    }

    // ── failure consequence chain: deduped Incident ────────────────────────────────

    @Test
    void failureOpensDedupedIncident(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        String target = "orders_" + System.nanoTime();
        String name = "email_nn_" + System.nanoTime();
        seedParquet(target, "SELECT * FROM (VALUES (1,NULL),(2,'x')) t(id,email)");
        try (Ctx c = open(cfg, wr)) {
            send(c.port, "POST", "/expectations", mk(name, target, "email", "non_null", null));
            String correlationId = "expectation:" + name;

            json(send(c.port, "POST", "/expectations/" + name + "/evaluate", null));
            assertEquals(1, c.svc.objects().active(ObjectType.INCIDENT, correlationId).size(),
                    "a failed evaluation opens one Incident");

            // re-evaluate while the Incident is still open → still exactly one (deduped)
            json(send(c.port, "POST", "/expectations/" + name + "/evaluate", null));
            assertEquals(1, c.svc.objects().active(ObjectType.INCIDENT, correlationId).size(),
                    "a second failure while the Incident is open does not open a duplicate");
        } finally {
            cleanup(target);
        }
    }

    // ── evaluate-all skips disabled ────────────────────────────────────────────────

    @Test
    void evaluateAllSkipsDisabled(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        String target = "orders_" + System.nanoTime();
        seedParquet(target, "SELECT * FROM (VALUES (1,NULL)) t(id,email)");
        try (Ctx c = open(cfg, wr)) {
            send(c.port, "POST", "/expectations",
                    "{\"name\":\"on\",\"target\":\"" + target + "\",\"column\":\"email\",\"kind\":\"non_null\"}");
            send(c.port, "POST", "/expectations",
                    "{\"name\":\"off\",\"target\":\"" + target + "\",\"column\":\"email\",\"kind\":\"non_null\","
                            + "\"enabled\":false}");

            JsonNode all = json(send(c.port, "POST", "/expectations/evaluate", null));
            assertEquals(2, all.size());
            for (JsonNode e : all) {
                if ("on".equals(e.get("name").asText()))
                    assertEquals("FAILED", e.get("lastResult").get("status").asText());
                else
                    assertTrue(e.get("lastResult").isNull(), "the disabled expectation is not evaluated");
            }
        } finally {
            cleanup(target);
        }
    }

    // ── MET-5: run-check stamps are not authoring edits ────────────────────────────

    @Test
    void evaluationStampsDoNotArchiveVersionsButEditsDo(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        String target = "orders_" + System.nanoTime();
        String name = "vh_" + System.nanoTime();
        seedParquet(target, "SELECT * FROM (VALUES (1,NULL),(2,'x')) t(id,email)");
        try (Ctx c = open(cfg, wr)) {
            send(c.port, "POST", "/expectations", mk(name, target, "email", "non_null", null));

            // Two run-checks persist lastResult — result stamps must NOT create versions, or
            // evaluation cadence would churn real config edits out of the keep-N window.
            json(send(c.port, "POST", "/expectations/" + name + "/evaluate", null));
            json(send(c.port, "POST", "/expectations/" + name + "/evaluate", null));
            JsonNode afterEvals = json(send(c.port, "GET", "/components/expectation/" + name + "/versions", null));
            assertEquals(0, afterEvals.size(), "run-check stamps must not create versions");

            // A real config edit DOES archive the outgoing copy.
            json(send(c.port, "PUT", "/expectations/" + name,
                    mk(name, target, "email", "non_null", "\"description\":\"edited\"")));
            JsonNode afterEdit = json(send(c.port, "GET", "/components/expectation/" + name + "/versions", null));
            assertEquals(1, afterEdit.size(), "an authoring edit archives one version");
        } finally {
            cleanup(target);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static String mk(String name, String target, String column, String kind, String extra) {
        return "{\"name\":\"" + name + "\",\"target\":\"" + target + "\",\"column\":\"" + column
                + "\",\"kind\":\"" + kind + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private void assertViolations(Ctx c, String body, long violations, String status) throws Exception {
        JsonNode created = json(send(c.port, "POST", "/expectations", body));
        String name = created.get("name").asText();
        JsonNode result = json(send(c.port, "POST", "/expectations/" + name + "/evaluate", null))
                .get("lastResult");
        assertEquals(status, result.get("status").asText(), name);
        assertEquals(violations, result.get("violations").asLong(), name);
    }

    /** Write a single-partition Parquet store under the legacy data root {@code ./database/<target>}. */
    private void seedParquet(String target, String selectSql) throws Exception {
        Path dir = Path.of("database").resolve(target).resolve("p=0");
        Files.createDirectories(dir);
        String parquet = dir.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("expectation_test_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (" + selectSql + ") TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static void cleanup(String target) {
        try (var paths = Files.walk(Path.of("database").resolve(target))) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // best-effort test cleanup
        }
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
