package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.DbJobRunStore;
import com.gamma.job.JobRun;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /jobs/runs} cursor pagination over real HTTP (api-contract-design §7): the v1 envelope's
 * {@code metadata.pagination} block carries {@code cursor/nextCursor/limit/total}, and walking the opaque
 * {@code nextCursor} pages the DuckDB run projection newest-first with no overlap and a null terminator.
 * The legacy (unversioned) view stays a bare JSON array. Uses an in-memory DuckDB job backend
 * ({@code -Djobs.backend=duckdb}, {@code jobs.db.url=jdbc:duckdb:}); runs are injected straight into the
 * store the route reads.
 */
class ControlApiJobRunsPageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        System.setProperty("jobs.backend", "duckdb");
        System.setProperty("jobs.db.url", "jdbc:duckdb:");
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        svc.jobServiceOrCreate();   // force the JobService + its DuckDB run store to open while the props are set
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @AfterEach
    void clearBackend() {
        System.clearProperty("jobs.backend");
        System.clearProperty("jobs.db.url");
    }

    private static JobRun run(String id, String start) {
        return new JobRun(id, "j1", "MAINTENANCE", "schedule", start, start, "SUCCESS", 10, "ok");
    }

    @Test
    void cursorPaginatesNewestFirstThroughTheEnvelope(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            DbJobRunStore store = c.svc.jobService().orElseThrow().runStore().orElseThrow();
            store.record(run("r1", "2026-06-17 10:00:00"));
            store.record(run("r2", "2026-06-17 10:01:00"));
            store.record(run("r3", "2026-06-17 10:02:00"));
            store.record(run("r4", "2026-06-17 10:03:00"));
            store.record(run("r5", "2026-06-17 10:04:00"));

            // page 1 — newest two, total across all pages, first-page cursor is null
            JsonNode e1 = json(get(c.port, "/api/v1/jobs/runs?limit=2"));
            assertEquals(List.of("r5", "r4"), ids(e1.get("data")));
            JsonNode pg1 = e1.get("metadata").get("pagination");
            assertEquals(5, pg1.get("total").asInt());
            assertEquals(2, pg1.get("limit").asInt());
            assertTrue(pg1.get("cursor").isNull(), "first page has no request cursor");
            String next1 = pg1.get("nextCursor").asText();
            assertFalse(next1.isBlank(), "more pages ⇒ a nextCursor");

            // page 2 — resumes strictly after r4, echoes the request cursor
            JsonNode e2 = json(get(c.port, "/api/v1/jobs/runs?limit=2&cursor=" + next1));
            assertEquals(List.of("r3", "r2"), ids(e2.get("data")), "no overlap with page 1");
            JsonNode pg2 = e2.get("metadata").get("pagination");
            assertEquals(next1, pg2.get("cursor").asText(), "request cursor echoed");
            String next2 = pg2.get("nextCursor").asText();

            // page 3 — final partial page, nextCursor exhausted
            JsonNode e3 = json(get(c.port, "/api/v1/jobs/runs?limit=2&cursor=" + next2));
            assertEquals(List.of("r1"), ids(e3.get("data")));
            assertTrue(e3.get("metadata").get("pagination").get("nextCursor").isNull(), "last page ⇒ null nextCursor");

            // legacy (unversioned) view is unchanged — a bare JSON array, no envelope/pagination
            JsonNode legacy = json(get(c.port, "/jobs/runs?limit=2"));
            assertTrue(legacy.isArray(), "legacy stays a raw list");
            assertEquals(2, legacy.size());
        }
    }

    private static List<String> ids(JsonNode dataArray) {
        return java.util.stream.StreamSupport.stream(dataArray.spliterator(), false)
                .map(n -> n.get("runId").asText()).toList();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
