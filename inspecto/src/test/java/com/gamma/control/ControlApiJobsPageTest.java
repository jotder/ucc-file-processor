package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobService;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /jobs} cursor pagination over real HTTP (api-contract-design §7): on the {@code /api/v1}
 * surface the v1 envelope's {@code metadata.pagination} block carries {@code cursor/nextCursor/limit/total},
 * and walking the opaque {@code nextCursor} pages the job registry name-ordered (single-part keyset —
 * names are unique) with no overlap, covering the whole set, and a null terminator on the last page.
 * The legacy (unversioned) view stays a bare JSON array with its {@code ?limit=&offset=} slice. Jobs are
 * hot-registered straight onto the live {@link JobService}, then exercised over the wire.
 */
class ControlApiJobsPageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        svc.jobServiceOrCreate();
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void cursorPaginatesNameOrderedThroughTheEnvelope(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JobService jobs = c.svc.jobService().orElseThrow();
            for (String name : List.of("job_d", "job_b", "job_e", "job_a", "job_c"))
                jobs.upsertJob(JobConfig.fromMap(Map.of("job", Map.of(
                        "name", name, "type", "maintenance", "task", "cleanup", "cron", "0 3 * * *"))));

            // page 1 — name-ordered, total across all pages, first-page request cursor is null
            JsonNode e1 = json(get(c.port, "/api/v1/jobs?limit=2"));
            assertEquals(List.of("job_a", "job_b"), names(e1.get("data")), "name-ordered keyset");
            JsonNode pg1 = e1.get("metadata").get("pagination");
            assertEquals(5, pg1.get("total").asInt(), "total spans every page, not just this one");
            assertEquals(2, pg1.get("limit").asInt());
            assertTrue(pg1.get("cursor").isNull(), "first page has no request cursor");

            // walk nextCursor to the end, collecting names
            List<String> walked = new ArrayList<>(names(e1.get("data")));
            JsonNode pg = pg1;
            int guard = 0;
            while (!pg.get("nextCursor").isNull()) {
                assertTrue(++guard < 10, "pagination must terminate");
                String next = pg.get("nextCursor").asText();
                JsonNode page = json(get(c.port, "/api/v1/jobs?limit=2&cursor=" + next));
                pg = page.get("metadata").get("pagination");
                assertEquals(next, pg.get("cursor").asText(), "request cursor echoed");
                assertTrue(page.get("data").size() <= 2, "no page exceeds the limit");
                walked.addAll(names(page.get("data")));
            }

            // every job seen exactly once, across all pages, in name order
            assertEquals(List.of("job_a", "job_b", "job_c", "job_d", "job_e"), walked,
                    "no overlap, nothing dropped, name-ordered end to end");
            assertEquals(walked.size(), new HashSet<>(walked).size(), "no name appears on two pages");

            // legacy (unversioned) view is unchanged — a bare JSON array with the offset slice
            JsonNode legacy = json(get(c.port, "/jobs?limit=2"));
            assertTrue(legacy.isArray(), "legacy stays a raw list");
            assertEquals(2, legacy.size());
        }
    }

    private static List<String> names(JsonNode dataArray) {
        return java.util.stream.StreamSupport.stream(dataArray.spliterator(), false)
                .map(n -> n.get("name").asText()).toList();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
