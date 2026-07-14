package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.EnrichmentConfig.Input;
import com.gamma.enrich.EnrichmentConfig.Output;
import com.gamma.enrich.EnrichmentConfig.Triggers;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobType;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the M3 {@link ControlApi} over real HTTP: health/readiness,
 * pipeline listing, single-pipeline trigger, audit queries
 * (commits/batches/files), pause/resume, config validation, reprocess, and 404s.
 */
class ControlApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** Wires a SourceService over one seeded pipeline + a started ControlApi. */
    private record Ctx(SourceService svc, ControlApi api, int port, String name) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        SourceService svc = new SourceService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), "test_etl");
    }

    /** Like {@link #open} but also registers a config-driven maintenance job. */
    private Ctx openWithJob(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n");
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        // keep the job audit under the test's temp dir, not the working directory
        System.setProperty("jobs.audit.dir", dir.resolve("jobs_audit").toString());
        SourceService svc = new SourceService(List.of(toon), List.of(), List.of(hb), 3600, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), "test_etl");
    }

    /** Like {@link #open} but also hosts a Stage-2 enrichment job that fires on the pipeline. */
    private Ctx openWithEnrichment(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        // enrichment reads the Stage-1 CSV output under <dir>/db, rolls up a daily count,
        // and is triggered by the pipeline's (lower-cased) name; audit lands in reports_audit.
        Path reports = dir.resolve("reports");
        EnrichmentConfig enrich = new EnrichmentConfig("DAILY_KPI",
                new Input(dir.resolve("db").toString().replace("\\", "/"), "CSV", List.of("year", "month", "day")),
                List.of(),
                new Output(reports.toString().replace("\\", "/"), "CSV", null, List.of("year", "month", "day")),
                "SELECT year, month, day, COUNT(*) AS n FROM input GROUP BY year, month, day",
                new Triggers("test_etl", 0));
        SourceService svc = new SourceService(List.of(toon), List.of(enrich), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        svc.start();   // immediate poll cycle: Stage-1 commit → event → enrichment recompute
        return new Ctx(svc, api, api.port(), "test_etl");
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

    @Test
    void healthAndReadyAreOpen(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> health = send(c.port, "GET", "/health", null);
            assertEquals(200, health.statusCode());
            assertEquals("UP", json(health).get("status").asText());

            HttpResponse<String> ready = send(c.port, "GET", "/ready", null);
            assertEquals(200, ready.statusCode());
            assertEquals(1, json(ready).get("pipelines").asInt());

            // a single-tenant server advertises multiSpace=false so the UI hides the switcher + CRUD
            HttpResponse<String> meta = send(c.port, "GET", "/spaces/_meta", null);
            assertEquals(200, meta.statusCode());
            assertFalse(json(meta).get("multiSpace").asBoolean());
        }
    }

    @Test
    void pipelinesAreListed(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> ok = send(c.port, "GET", "/runs", null);
            assertEquals(200, ok.statusCode());
            JsonNode arr = json(ok);
            assertTrue(arr.isArray() && arr.size() == 1);
            assertEquals(c.name, arr.get(0).get("name").asText());
            assertFalse(arr.get(0).get("paused").asBoolean());
        }
    }

    @Test
    void triggerRunsPipelineThenAuditQueriesReturnData(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> run = send(c.port, "POST", "/runs/" + c.name + "/trigger", null);
            assertEquals(200, run.statusCode());
            assertEquals(1, json(run).get("total").asInt());
            assertEquals(0, json(run).get("failed").asInt());

            assertFalse(json(send(c.port, "GET", "/runs/" + c.name + "/commits", null)).isEmpty(),
                    "a committed batch should be visible");
            assertTrue(json(send(c.port, "GET", "/runs/" + c.name + "/batches", null)).size() >= 1,
                    "batch audit rows present");
            assertTrue(json(send(c.port, "GET", "/runs/" + c.name + "/files", null)).size() >= 1,
                    "file audit rows present");
            // lineage carries partition rows for the committed batch
            assertTrue(json(send(c.port, "GET", "/runs/" + c.name + "/lineage", null)).size() >= 1);
        }
    }

    @Test
    void pauseAndResumeToggleState(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "POST", "/runs/" + c.name + "/pause", null).statusCode());
            JsonNode listed = json(send(c.port, "GET", "/runs", null));
            assertTrue(listed.get(0).get("paused").asBoolean(), "pipeline reports paused");

            assertEquals(200, send(c.port, "POST", "/runs/" + c.name + "/resume", null).statusCode());
            JsonNode after = json(send(c.port, "GET", "/runs", null));
            assertFalse(after.get(0).get("paused").asBoolean(), "pipeline reports resumed");
        }
    }

    @Test
    void validateReturnsConfigWarnings(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path toon = c.svc.pathFor(c.name).orElseThrow();
            String body = "{\"configPath\":\"" + toon.toString().replace("\\", "/") + "\"}";
            HttpResponse<String> r = send(c.port, "POST", "/validate", body);
            assertEquals(200, r.statusCode());
            assertEquals(c.name, json(r).get("pipeline").asText());
            assertTrue(json(r).has("warnings"));
        }
    }

    @Test
    void unknownPipelineAndPathYield404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, send(c.port, "GET", "/runs/nope/commits", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/no/such/route", null).statusCode());
            assertEquals(405, send(c.port, "GET", "/trigger", null).statusCode(), "GET on POST-only route");
        }
    }

    @Test
    void metricsEndpointIsOpenAndReflectsARun(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.svc.start();   // wires MetricsService onto the bus + runs an immediate poll cycle
            // wait for the scheduled cycle to commit at least one batch
            long deadline = System.nanoTime() + 8_000_000_000L;
            String body = "";
            while (System.nanoTime() < deadline) {
                HttpResponse<String> m = send(c.port, "GET", "/metrics", null);  // open, no token
                assertEquals(200, m.statusCode());
                assertTrue(m.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
                body = m.body();
                if (body.contains("inspecto_batches_total")) break;
                Thread.sleep(150);
            }
            assertTrue(body.contains("# TYPE inspecto_batches_total counter"), "Prometheus exposition present");
            assertTrue(body.contains("inspecto_batches_total{pipeline=\"test_etl\",status=\"SUCCESS\"}"),
                    "a committed batch was counted:\n" + body);
            assertTrue(body.contains("inspecto_poll_cycles_total"), "poll cycle counted");
            assertTrue(body.contains("inspecto_committed_batches{pipeline=\"test_etl\"}"),
                    "scrape-time gauge populated");
        }
    }

    @Test
    void statusAndBatchReportEndpoints(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            send(c.port, "POST", "/runs/" + c.name + "/trigger", null);

            JsonNode status = json(send(c.port, "GET", "/status", null));
            assertEquals(1, status.get("pipelineCount").asInt());
            assertTrue(status.get("totalCommittedBatches").asLong() >= 1);
            assertEquals(c.name, status.get("pipelines").get(0).get("pipeline").asText());

            JsonNode report = json(send(c.port, "GET", "/report", null));
            assertTrue(report.get("totalBatches").asLong() >= 1);
            assertEquals(report.get("totalBatches").asLong(), report.get("success").asLong());

            JsonNode one = json(send(c.port, "GET", "/runs/" + c.name + "/report", null));
            assertEquals(c.name, one.get("pipeline").asText());
            assertTrue(one.get("totalBatches").asLong() >= 1);

            // unknown pipeline report → 404
            assertEquals(404, send(c.port, "GET", "/runs/ghost/report", null).statusCode());
            // no jobs registered → an empty list (collection convention), not 404
            HttpResponse<String> noJobs = send(c.port, "GET", "/jobs", null);
            assertEquals(200, noJobs.statusCode(), "empty job list is a 200, not a 404");
            assertTrue(json(noJobs).isArray() && json(noJobs).isEmpty(), "no jobs registered → []");
        }
    }

    @Test
    void reportDateRangeAndPercentiles(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            send(c.port, "POST", "/runs/" + c.name + "/trigger", null);

            // unbounded report carries the new percentile + window fields
            JsonNode all = json(send(c.port, "GET", "/runs/" + c.name + "/report", null));
            assertTrue(all.get("totalBatches").asLong() >= 1);
            assertTrue(all.has("p50DurationMs") && all.has("p95DurationMs") && all.has("p99DurationMs"));
            assertEquals("", all.get("windowFrom").asText(), "unbounded → blank window echo");

            // a far-past window scopes the batch (run is 'today') out → zeroed, window echoed
            JsonNode past = json(send(c.port, "GET",
                    "/runs/" + c.name + "/report?from=2000-01-01&to=2000-12-31", null));
            assertEquals(0, past.get("totalBatches").asLong(), "today's batch is outside a 2000 window");
            assertEquals("2000-01-01", past.get("windowFrom").asText());
            assertEquals("2000-12-31 23:59:59", past.get("windowTo").asText(), "date-only to widened");

            // a wide window includes it again
            JsonNode wide = json(send(c.port, "GET",
                    "/runs/" + c.name + "/report?from=2020-01-01&to=2030-12-31", null));
            assertEquals(all.get("totalBatches").asLong(), wide.get("totalBatches").asLong());

            // service-wide report honours the range too
            JsonNode svcPast = json(send(c.port, "GET", "/report?from=2000-01-01&to=2000-12-31", null));
            assertEquals(0, svcPast.get("totalBatches").asLong());
            assertTrue(svcPast.has("p50DurationMs"));
        }
    }

    @Test
    void jobsEndpointsListTriggerAndHistory(@TempDir Path dir) throws Exception {
        try (Ctx c = openWithJob(dir)) {
            JsonNode list = json(send(c.port, "GET", "/jobs", null));
            assertTrue(list.isArray() && list.size() == 1);
            assertEquals("hb", list.get(0).get("name").asText());
            assertEquals("maintenance", list.get(0).get("type").asText());   // P2b: type is the lowercased registry id

            HttpResponse<String> trig = send(c.port, "POST", "/jobs/hb/trigger", null);
            assertEquals(200, trig.statusCode());
            assertEquals("triggered", json(trig).get("status").asText());

            // history populates asynchronously — poll briefly
            long deadline = System.nanoTime() + 5_000_000_000L;
            JsonNode runs = json(send(c.port, "GET", "/jobs/hb/runs", null));
            while (runs.size() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(100);
                runs = json(send(c.port, "GET", "/jobs/hb/runs", null));
            }
            assertTrue(runs.size() >= 1, "a run is recorded in history");
            assertEquals("SUCCESS", runs.get(0).get("status").asText());

            assertEquals(404, send(c.port, "POST", "/jobs/nope/trigger", null).statusCode());
        }
    }

    @Test
    void enrichmentEndpointsListRunsLineageAndReport(@TempDir Path dir) throws Exception {
        try (Ctx c = openWithEnrichment(dir)) {
            // the recompute runs asynchronously off the poll cycle — poll until its run audit lands
            long deadline = System.nanoTime() + 15_000_000_000L;
            JsonNode runs = json(send(c.port, "GET", "/enrichment/DAILY_KPI/runs", null));
            while (runs.size() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(150);
                runs = json(send(c.port, "GET", "/enrichment/DAILY_KPI/runs", null));
            }
            assertTrue(runs.size() >= 1, "an enrichment run audit row is surfaced");
            assertEquals("SUCCESS", runs.get(0).get("status").asText());
            String runId = runs.get(0).get("run_id").asText();

            // listing reflects the job + its last run
            JsonNode list = json(send(c.port, "GET", "/enrichment", null));
            assertTrue(list.isArray() && list.size() == 1);
            assertEquals("DAILY_KPI", list.get(0).get("name").asText());
            assertEquals("test_etl", list.get(0).get("onPipeline").asText());
            assertTrue(list.get(0).get("eventTriggered").asBoolean());
            assertTrue(list.get(0).get("runCount").asInt() >= 1);

            // lineage rows, and the exact runId filter
            assertTrue(json(send(c.port, "GET", "/enrichment/DAILY_KPI/lineage", null)).size() >= 1);
            JsonNode scoped = json(send(c.port, "GET",
                    "/enrichment/DAILY_KPI/lineage?runId=" + runId, null));
            assertTrue(scoped.size() >= 1, "lineage filtered to the run");
            assertEquals(runId, scoped.get(0).get("run_id").asText());

            // aggregated run-audit rollup
            JsonNode report = json(send(c.port, "GET", "/enrichment/DAILY_KPI/report", null));
            assertEquals("DAILY_KPI", report.get("job").asText());
            assertTrue(report.get("totalRuns").asLong() >= 1);
            assertEquals(report.get("totalRuns").asLong(), report.get("success").asLong());
            assertEquals(0.0, report.get("errorRate").asDouble());

            // 404s
            assertEquals(404, send(c.port, "GET", "/enrichment/ghost/runs", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/enrichment/ghost/report", null).statusCode());
        }
    }

    @Test
    void enrichmentEndpointsAre404WhenNoEnrichmentRegistered(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, send(c.port, "GET", "/enrichment", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/enrichment/any/runs", null).statusCode());
        }
    }

    @Test
    void reprocessReplaysACommittedBatch(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            send(c.port, "POST", "/runs/" + c.name + "/trigger", null);
            JsonNode commits = json(send(c.port, "GET", "/runs/" + c.name + "/commits", null));
            assertFalse(commits.isEmpty());
            String batchId = commits.get(0).asText();

            String body = "{\"batchId\":\"" + batchId + "\"}";
            HttpResponse<String> r = send(c.port, "POST", "/runs/" + c.name + "/reprocess", body);
            assertEquals(200, r.statusCode(), "reprocess body: " + r.body());
            assertEquals("reprocessed", json(r).get("status").asText());
            assertEquals(batchId, json(r).get("batchId").asText());
        }
    }
}
