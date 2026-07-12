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
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real-HTTP tests for {@code GET /health/details} (System Maintenance MNT-15). */
class ControlApiHealthDetailsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a real service+API; {@code writeRoot} is passed verbatim so a test can point it at garbage. */
    private Ctx open(Path cfg, String writeRoot, List<JobConfig> jobs) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot);
        try {
            SourceService svc = new SourceService(List.of(pipe), List.of(), jobs, 3600L, 1, null);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private JsonNode details(int port) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/health/details")).GET().build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body());
    }

    @Test
    void reportsPerSubsystemStatusWithJobsRegistered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JobConfig hb = new JobConfig("hd-hb", JobType.MAINTENANCE, "0 3 * * *", null, true, false, Map.of("task", "heartbeat"));
        try (Ctx c = open(cfg, root.toString(), List.of(hb))) {
            JsonNode body = details(c.port);
            assertEquals("UP", body.get("status").asText(), body.toString());
            JsonNode subs = body.get("subsystems");
            assertEquals("UP", subs.get("configStore").get("status").asText(), subs.toString());
            assertEquals("UP", subs.get("scheduler").get("status").asText(), subs.toString());
            assertTrue(subs.get("scheduler").get("detail").asText().contains("1 job(s), 1 cron-scheduled"), subs.toString());
            assertEquals("NOT_CONFIGURED", subs.get("jobRunsProjection").get("status").asText(),
                    "-Djobs.backend unset in this harness: " + subs);
            assertEquals("UP", subs.get("pipelines").get("status").asText(), subs.toString());
        }
    }

    @Test
    void unconfiguredSubsystemsAreNotFailures(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root.toString(), List.of())) {   // no jobs at all
            JsonNode body = details(c.port);
            assertEquals("UP", body.get("status").asText(), "absence of optional subsystems is not DOWN: " + body);
            assertEquals("NOT_CONFIGURED", body.get("subsystems").get("scheduler").get("status").asText());
        }
    }

    @Test
    void brokenWriteRootFlagsConfigStoreDown(@TempDir Path cfg, @TempDir Path junk) throws Exception {
        Path file = Files.writeString(junk.resolve("not-a-dir"), "x");   // a FILE as write root
        try (Ctx c = open(cfg, file.toString(), List.of())) {
            JsonNode body = details(c.port);
            assertEquals("DOWN", body.get("status").asText(), body.toString());
            assertEquals("DOWN", body.get("subsystems").get("configStore").get("status").asText(), body.toString());
        }
    }
}
