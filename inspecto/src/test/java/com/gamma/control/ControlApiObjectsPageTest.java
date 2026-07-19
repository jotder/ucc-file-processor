package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /objects} cursor pagination over real HTTP (api-contract-design §7): on the {@code /api/v1}
 * surface the v1 envelope's {@code metadata.pagination} block carries {@code cursor/nextCursor/limit/total},
 * and walking the opaque {@code nextCursor} pages the objects newest-first (keyset {@code (createdAt, id)})
 * with no overlap, covering the whole set, and a null terminator on the last page. The legacy (unversioned)
 * view stays a bare JSON array with its {@code ?limit=&offset=} slice. Objects are seeded through the
 * service API, then exercised over the wire.
 */
class ControlApiObjectsPageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void cursorPaginatesThroughTheEnvelopeWithNoOverlap(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Set<String> allIds = new HashSet<>();
            for (int i = 1; i <= 5; i++)
                allIds.add(c.svc.objects().open(ObjectType.ALERT, "alert " + i, "msg", "CRITICAL", "pipeA",
                        Map.of()).id());

            // page 1 — total across all pages, first-page request cursor is null
            JsonNode e1 = json(get(c.port, "/api/v1/objects?type=ALERT&limit=2"));
            JsonNode pg1 = e1.get("metadata").get("pagination");
            assertEquals(5, pg1.get("total").asInt(), "total spans every page, not just this one");
            assertEquals(2, pg1.get("limit").asInt());
            assertTrue(pg1.get("cursor").isNull(), "first page has no request cursor");
            assertEquals(2, e1.get("data").size());

            // walk nextCursor to the end, collecting ids
            List<String> walked = new ArrayList<>(ids(e1.get("data")));
            JsonNode pg = pg1;
            JsonNode page = e1;
            int guard = 0;
            while (!pg.get("nextCursor").isNull()) {
                assertTrue(++guard < 10, "pagination must terminate");
                String next = pg.get("nextCursor").asText();
                page = json(get(c.port, "/api/v1/objects?type=ALERT&limit=2&cursor=" + next));
                pg = page.get("metadata").get("pagination");
                assertEquals(next, pg.get("cursor").asText(), "request cursor echoed");
                assertTrue(page.get("data").size() <= 2, "no page exceeds the limit");
                walked.addAll(ids(page.get("data")));
            }

            // every object seen exactly once, across all pages
            assertEquals(5, walked.size(), "no overlap and nothing dropped");
            assertEquals(allIds, new HashSet<>(walked), "the pages cover the whole set");
            assertEquals(walked.size(), new HashSet<>(walked).size(), "no id appears on two pages");

            // legacy (unversioned) view is unchanged — a bare JSON array, no envelope/pagination
            JsonNode legacy = json(get(c.port, "/objects?type=ALERT&limit=2"));
            assertTrue(legacy.isArray(), "legacy stays a raw list");
            assertEquals(2, legacy.size());
        }
    }

    private static List<String> ids(JsonNode dataArray) {
        return java.util.stream.StreamSupport.stream(dataArray.spliterator(), false)
                .map(n -> n.get("id").asText()).toList();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
