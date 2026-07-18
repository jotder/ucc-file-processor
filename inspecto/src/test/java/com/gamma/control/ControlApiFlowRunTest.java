package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The config-less ad-hoc flow run (T32 follow-up) over real HTTP: {@code POST /pipelines/authored/{id}/trigger}
 * (mirrors {@code POST /jobs/{name}/trigger}; {@code …/run} is reserved for the editor's scratch-only
 * run-to-here contract) — 503 without a write root, 404 for a missing/unsafe flow id, and the happy path:
 * 202 + runId polled to SUCCESS via {@code GET /jobs/runs/{runId}}, the sink store written, and no job
 * registered ({@code GET /jobs} stays empty — the run is ad-hoc, not a {@code *_job.toon}).
 */
class ControlApiFlowRunTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** An authored job-style flow: at-rest source store {@code events} → filter → sink store {@code rollup}. */
    private static final String FLOW = """
        {"name":"evt_rollup","active":false,
         "nodes":[{"id":"src","type":"acquisition","config":{"source_store":"events"}},
                  {"id":"flt","type":"transform.filter","config":{"where":"amt >= 100"}},
                  {"id":"out","type":"sink.persistent","config":{"store":"rollup"}}],
         "edges":[{"from":"src","rel":"data","to":"flt"},{"from":"flt","rel":"data","to":"out"}]}""";

    /** Unlike the CRUD harness, the properties are set BEFORE the service is constructed (the boot order
     *  of a real deployment) and held until {@link Ctx#close()}: the run path resolves the service's own
     *  flow store / data root ({@code jobServiceOrCreate}), which are wired at construction/request time —
     *  not just the ControlApi write gate captured at API construction. */
    private record Ctx(CollectorService svc, ControlApi api, int port,
                       String priorRoot, String priorData, String priorAudit) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            restore("assist.write.root", priorRoot);
            restore("data.dir", priorData);
            restore("jobs.audit.dir", priorAudit);
        }
        private static void restore(String key, String v) {
            if (v != null) System.setProperty(key, v); else System.clearProperty(key);
        }
    }

    private Ctx open(Path dir, Path writeRoot, Path dataDir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        String priorRoot = System.getProperty("assist.write.root");
        String priorData = System.getProperty("data.dir");
        String priorAudit = System.getProperty("jobs.audit.dir");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        if (dataDir != null) System.setProperty("data.dir", dataDir.toString());
        else System.clearProperty("data.dir");
        System.setProperty("jobs.audit.dir", dir.resolve("jobs_audit").toString());
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), priorRoot, priorData, priorAudit);
    }

    @Test
    void adhocRunExecutesTheFlowAndIsPollable(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        try (Ctx c = open(dir, dir.resolve("wr"), dataDir)) {
            assertEquals(200, send(c.port, "POST", "/pipelines/authored", FLOW).statusCode());

            HttpResponse<String> r = send(c.port, "POST", "/pipelines/authored/evt_rollup/trigger?actor=rahul", null);
            assertEquals(202, r.statusCode(), r.body());
            String runId = json(r).get("runId").asText();
            assertEquals("evt_rollup", json(r).get("pipeline").asText());
            assertEquals("/jobs/runs/" + runId, r.headers().firstValue("Location").orElseThrow());

            JsonNode run = awaitTerminal(c.port, runId);
            assertEquals("SUCCESS", run.get("status").asText(), run.toString());
            assertEquals("manual:rahul", run.get("trigger").asText(), "the fire is actor-attributed");
            assertEquals("evt_rollup", run.get("job").asText(), "the run is recorded under the flow id");
            try (Stream<Path> out = Files.walk(dataDir.resolve("rollup"))) {
                assertTrue(out.anyMatch(p -> p.toString().endsWith(".parquet")), "the sink store was written");
            }

            // config-less means config-less: nothing was registered in the job registry
            assertEquals(0, json(send(c.port, "GET", "/jobs", null)).size());
        }
    }

    @Test
    void runIsGatedOnTheWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null, null)) {
            assertEquals(503, send(c.port, "POST", "/pipelines/authored/any/trigger", null).statusCode());
        }
    }

    @Test
    void missingOrUnsafeFlowIdIsNotFound(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"), dir.resolve("data"))) {
            assertEquals(404, send(c.port, "POST", "/pipelines/authored/ghost/trigger", null).statusCode());
            // an unsafe id (rejected by PipelineStore's SAFE_ID) is "not present" → 404, never a 500
            assertEquals(404, send(c.port, "POST", "/pipelines/authored/__nope__/trigger", null).statusCode());
        }
    }

    /** Poll {@code GET /jobs/runs/{runId}} until the run leaves RUNNING (or 15s elapse). */
    private JsonNode awaitTerminal(int port, String runId) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode run = json(send(port, "GET", "/jobs/runs/" + runId, null));
            if (!"RUNNING".equals(run.get("status").asText())) return run;
            Thread.sleep(100);
        }
        throw new AssertionError("run " + runId + " did not reach a terminal state within 15s");
    }

    /** Write {@code (id,amt)} VALUES as a Parquet file under {@code <dataDir>/<store>/} (an at-rest source store). */
    private static void seedParquet(Path dataDir, String store, String valuesSql) throws Exception {
        Path d = dataDir.resolve(store);
        Files.createDirectories(d);
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + valuesSql + ") t(id,amt)) TO '"
                    + d.resolve("seed.parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
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
