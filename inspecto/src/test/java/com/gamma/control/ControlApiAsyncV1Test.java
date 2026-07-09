package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for W5: {@code Idempotency-Key} replay on retryable writes, and the async
 * {@code 202 + runId + Location} + {@code GET /jobs/runs/{runId}} poll pattern for job triggers.
 */
class ControlApiAsyncV1Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot, List<JobConfig> jobs) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        System.setProperty("jobs.audit.dir", writeRoot.resolve("jobs_audit").toString());
        try {
            SourceService svc = new SourceService(List.of(pipe), List.of(), jobs, 3600L, 1, null);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
            System.clearProperty("jobs.audit.dir");
        }
    }

    private HttpResponse<String> post(int port, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    // ── idempotency ────────────────────────────────────────────────────────────────

    @Test
    void idempotencyKeyReplaysTheFirstResponse(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, List.of())) {
            String widget = "{\"id\":\"w1\",\"kind\":\"bar\"}";
            HttpResponse<String> first = post(c.port, "/api/v1/components/widget", widget, "Idempotency-Key", "k1");
            assertEquals(200, first.statusCode(), first.body());
            assertTrue(first.headers().firstValue("Idempotency-Replayed").isEmpty());

            // Same key → the FIRST response replays verbatim (no second create, so NOT the 409 a real retry would hit).
            HttpResponse<String> replay = post(c.port, "/api/v1/components/widget", widget, "Idempotency-Key", "k1");
            assertEquals(200, replay.statusCode(), "replayed, not 409");
            assertEquals("true", replay.headers().firstValue("Idempotency-Replayed").orElse(null));
            assertEquals(first.body(), replay.body(), "byte-identical replay of the original response");

            // A keyless retry proves the resource really exists now — it 409s.
            assertEquals(409, post(c.port, "/api/v1/components/widget", widget).statusCode());
        }
    }

    // ── async job trigger + poll ─────────────────────────────────────────────────────

    @Test
    void jobTriggerIsAcceptedWithRunIdAndPollable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        try (Ctx c = open(cfg, root, List.of(hb))) {
            HttpResponse<String> accepted = post(c.port, "/api/v1/jobs/hb/trigger", null);
            assertEquals(202, accepted.statusCode(), accepted.body());
            JsonNode data = JSON.readTree(accepted.body()).get("data");
            String runId = data.get("runId").asText();
            assertFalse(runId.isBlank());
            assertEquals("running", data.get("status").asText());
            assertEquals("/api/v1/jobs/runs/" + runId, accepted.headers().firstValue("Location").orElse(null));

            // Poll the run to a terminal status.
            String status = null;
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                HttpResponse<String> polled = get(c.port, "/api/v1/jobs/runs/" + runId);
                assertEquals(200, polled.statusCode(), polled.body());
                JsonNode run = JSON.readTree(polled.body()).get("data");
                assertEquals(runId, run.get("runId").asText());
                status = run.get("status").asText();
                if (!"RUNNING".equals(status)) break;
                Thread.sleep(50);
            }
            assertEquals("SUCCESS", status, "the heartbeat maintenance job succeeds");
        }
    }

    @Test
    void legacyJobTriggerResponseIsUnchanged(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        try (Ctx c = open(cfg, root, List.of(hb))) {
            HttpResponse<String> r = post(c.port, "/jobs/hb/trigger", null);   // unversioned surface
            assertEquals(200, r.statusCode());
            JsonNode body = JSON.readTree(r.body());
            assertEquals("triggered", body.get("status").asText());
            assertEquals("hb", body.get("job").asText());
            assertNull(body.get("data"), "legacy surface stays un-enveloped and un-changed (200, no runId/202)");
        }
    }

    @Test
    void unknownJobTriggerIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        try (Ctx c = open(cfg, root, List.of(hb))) {
            assertEquals(404, post(c.port, "/api/v1/jobs/ghost/trigger", null).statusCode());
            assertEquals(404, get(c.port, "/api/v1/jobs/runs/does-not-exist").statusCode());
        }
    }

    // ── Run Log (job-framework P0, R5) ───────────────────────────────────────────────

    @Test
    void runLogIsPersistedAndServedWithTheParamSnapshot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        try (Ctx c = open(cfg, root, List.of(hb))) {
            String runId = JSON.readTree(post(c.port, "/api/v1/jobs/hb/trigger", null).body()).get("data").get("runId").asText();
            // wait for the run to reach a terminal status so the "run completed" entry is written
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                JsonNode run = JSON.readTree(get(c.port, "/api/v1/jobs/runs/" + runId).body()).get("data");
                if (!"RUNNING".equals(run.get("status").asText())) break;
                Thread.sleep(50);
            }
            HttpResponse<String> logResp = get(c.port, "/jobs/hb/runs/" + runId + "/log");   // unversioned: raw array
            assertEquals(200, logResp.statusCode(), logResp.body());
            JsonNode entries = JSON.readTree(logResp.body());
            assertTrue(entries.isArray() && entries.size() >= 2, "run start + completion logged: " + logResp.body());

            List<String> messages = new ArrayList<>();
            entries.forEach(n -> messages.add(n.get("message").asText()));
            assertTrue(messages.contains("run started"), messages.toString());
            assertTrue(messages.contains("run completed"), messages.toString());

            JsonNode start = entries.get(0);
            assertEquals("run started", start.get("message").asText(), "the first entry is the start (seq 1)");
            assertEquals("heartbeat", start.get("kv").get("params").get("task").asText(),
                    "the resolved param snapshot is recorded: " + start);
        }
    }

    @Test
    void runLogForAnUnknownRunIsAnEmptyArray(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        try (Ctx c = open(cfg, root, List.of(hb))) {
            HttpResponse<String> r = get(c.port, "/jobs/hb/runs/never-ran-1/log");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = JSON.readTree(r.body());
            assertTrue(body.isArray() && body.isEmpty(), "unknown run → empty log, not 404");
        }
    }

    // ── Signal ledger (job-framework P1a, R6) ────────────────────────────────────────

    /** Trigger a heartbeat job and wait for it to reach a terminal status; returns its runId. */
    private String triggerAndAwait(Ctx c, String job) throws Exception {
        String runId = JSON.readTree(post(c.port, "/api/v1/jobs/" + job + "/trigger", null).body())
                .get("data").get("runId").asText();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode run = JSON.readTree(get(c.port, "/api/v1/jobs/runs/" + runId).body()).get("data");
            if (!"RUNNING".equals(run.get("status").asText())) break;
            Thread.sleep(50);
        }
        return runId;
    }

    @Test
    void jobLifecycleSignalsLandOnTheLedgerWithTheEnvelope(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // Unique job name → unique runId (= correlationId): EventLog.global() is process-wide and drains
        // forward across the per-test SourceServices, and runIds are only second-precision + a per-instance
        // seq, so a shared "hb" name would collide with other tests' hb runs in the same second.
        JobConfig hb = new JobConfig("sigjob", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        try (Ctx c = open(cfg, root, List.of(hb))) {
            String runId = triggerAndAwait(c, "sigjob");

            HttpResponse<String> resp = get(c.port, "/signals?correlationId=" + runId);
            assertEquals(200, resp.statusCode(), resp.body());
            JsonNode arr = JSON.readTree(resp.body());
            assertTrue(arr.isArray() && arr.size() >= 2, "started + completed on the ledger: " + resp.body());

            List<String> types = new ArrayList<>();
            JsonNode completed = null;
            for (JsonNode n : arr) {
                types.add(n.get("type").asText());
                if ("job.run.completed".equals(n.get("type").asText())) completed = n;
            }
            assertTrue(types.contains("job.run.started"), types.toString());
            assertTrue(types.contains("job.run.completed"), types.toString());

            assertNotNull(completed, "a completed signal was emitted");
            assertEquals(runId, completed.get("correlationId").asText(), "correlated to the run");
            assertEquals("INFO", completed.get("severity").asText());
            assertFalse(completed.get("signalId").asText().isBlank(), "framework-stamped id");
            assertEquals("SUCCESS", completed.get("payload").get("outcome").asText(), completed.toString());
        }
    }

    @Test
    void signalTypeFilterSupportsExactAndPrefixGlob(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JobConfig hb = new JobConfig("globjob", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        try (Ctx c = open(cfg, root, List.of(hb))) {
            String runId = triggerAndAwait(c, "globjob");

            // Exact filter admits ONLY that type; unique runId isolates this run's single started signal.
            JsonNode exact = JSON.readTree(get(c.port, "/signals?type=job.run.started&correlationId=" + runId).body());
            assertEquals(1, exact.size(), "exact type filter isolates one signal: " + exact);
            exact.forEach(n -> assertEquals("job.run.started", n.get("type").asText()));

            // Prefix glob matches the whole job.run.* family (started + completed) — a strict superset.
            JsonNode glob = JSON.readTree(get(c.port, "/signals?type=job.run.*&correlationId=" + runId).body());
            assertTrue(glob.size() >= 2, "prefix glob matches all job.run.* : " + glob);
            List<String> globTypes = new ArrayList<>();
            glob.forEach(n -> globTypes.add(n.get("type").asText()));
            assertTrue(globTypes.contains("job.run.completed"), "glob matches more than the exact type: " + globTypes);
        }
    }

    // ── async pipeline trigger + poll (W5b) ──────────────────────────────────────────

    @Test
    void pipelineTriggerIsAcceptedWithRunIdAndPollable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, List.of())) {   // Ctx writes one pipeline; take its registered id
            String pipe = c.svc.pipelines().get(0).name();
            HttpResponse<String> accepted = post(c.port, "/api/v1/runs/" + pipe + "/trigger", null);
            assertEquals(202, accepted.statusCode(), accepted.body());
            JsonNode data = JSON.readTree(accepted.body()).get("data");
            String runId = data.get("runId").asText();
            assertFalse(runId.isBlank());
            assertEquals(pipe, data.get("pipeline").asText());
            assertEquals("running", data.get("status").asText());
            assertEquals("/api/v1/runs/runs/" + runId, accepted.headers().firstValue("Location").orElse(null));

            // Poll the run to a terminal status.
            String status = null;
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                HttpResponse<String> polled = get(c.port, "/api/v1/runs/runs/" + runId);
                assertEquals(200, polled.statusCode(), polled.body());
                JsonNode run = JSON.readTree(polled.body()).get("data");
                assertEquals(runId, run.get("runId").asText());
                status = run.get("status").asText();
                if (!"RUNNING".equals(status)) break;
                Thread.sleep(50);
            }
            assertEquals("SUCCESS", status, "the empty-inbox run completes without failures");
        }
    }

    @Test
    void legacyPipelineTriggerResponseIsUnchanged(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, List.of())) {
            String pipe = c.svc.pipelines().get(0).name();
            HttpResponse<String> r = post(c.port, "/runs/" + pipe + "/trigger", null);   // unversioned surface
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = JSON.readTree(r.body());
            assertTrue(body.has("total") && body.has("failed"), "legacy body is the raw RunResult");
            assertNull(body.get("data"), "legacy surface stays un-enveloped (200, no runId/202)");
        }
    }

    @Test
    void unknownPipelineTriggerIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, List.of())) {
            assertEquals(404, post(c.port, "/api/v1/runs/ghost/trigger", null).statusCode());
            assertEquals(404, get(c.port, "/api/v1/runs/runs/does-not-exist").statusCode());
        }
    }
}
