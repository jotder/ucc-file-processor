package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.event.EventLevel;
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
 * {@code GET /events} cursor pagination over real HTTP (api-contract-design §7): on the {@code /api/v1}
 * surface the v1 envelope's {@code metadata.pagination} block carries {@code cursor/nextCursor/limit/total},
 * and walking the opaque {@code nextCursor} pages the retained event history newest-first (keyset
 * {@code (ts, eventId)} — including a shared-timestamp pair, which the id tiebreak must resume through
 * unambiguously) with no overlap. (The null terminator is covered by the {@code /jobs} adopter's walk —
 * here service-generated events trail the fixtures, so the history has no fixed end.) The legacy
 * (unversioned) view stays the bare live-tail JSON array. Events are appended straight onto the store
 * the route reads.
 */
class ControlApiEventsPageTest {

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

    private static Event event(String id, long ts) {
        return new Event(id, ts, EventLevel.INFO, "TEST", "test-src", "pipeA", "corr-1",
                "event " + id, Map.of(), Map.of());
    }

    @Test
    void cursorPaginatesNewestFirstWithIdTiebreak(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // The store also holds service-generated events (captured logs), so the fixtures are stamped
            // far in the future to be the five newest, and totals are asserted as lower bounds.
            long base = System.currentTimeMillis() + 1_000_000_000L;
            // e3/e4 share a timestamp — the eventId tiebreak must resume through them without drift
            c.svc.events().append(event("e1", base + 1_000L));
            c.svc.events().append(event("e2", base + 2_000L));
            c.svc.events().append(event("e3", base + 3_000L));
            c.svc.events().append(event("e4", base + 3_000L));
            c.svc.events().append(event("e5", base + 4_000L));

            // page 1 — newest two, total across all pages, first-page request cursor is null
            JsonNode e1 = json(get(c.port, "/api/v1/events?limit=2"));
            assertEquals(List.of("e5", "e4"), ids(e1.get("data")), "newest-first, id DESC on the tie");
            JsonNode pg1 = e1.get("metadata").get("pagination");
            assertTrue(pg1.get("total").asInt() >= 5, "total spans the whole retained history");
            assertEquals(2, pg1.get("limit").asInt());
            assertTrue(pg1.get("cursor").isNull(), "first page has no request cursor");
            String next1 = pg1.get("nextCursor").asText();
            assertFalse(next1.isBlank(), "more pages ⇒ a nextCursor");

            // page 2 — resumes strictly after e4 (the shared-ts row), echoes the request cursor
            JsonNode e2 = json(get(c.port, "/api/v1/events?limit=2&cursor=" + next1));
            assertEquals(List.of("e3", "e2"), ids(e2.get("data")), "tiebreak resume — e3 not skipped, e4 not repeated");
            JsonNode pg2 = e2.get("metadata").get("pagination");
            assertEquals(next1, pg2.get("cursor").asText(), "request cursor echoed");
            String next2 = pg2.get("nextCursor").asText();

            // page 3 — starts with the last fixture; service events (older) may follow it
            JsonNode e3 = json(get(c.port, "/api/v1/events?limit=2&cursor=" + next2));
            assertFalse(ids(e3.get("data")).isEmpty());
            assertEquals("e1", ids(e3.get("data")).get(0), "the walk reaches every fixture in order");

            // no overlap across the walk
            List<String> walked = new ArrayList<>(ids(e1.get("data")));
            walked.addAll(ids(e2.get("data")));
            walked.addAll(ids(e3.get("data")));
            assertEquals(walked.size(), new HashSet<>(walked).size(), "no event appears on two pages");

            // legacy (unversioned) view is unchanged — the bare live-tail JSON array
            JsonNode legacy = json(get(c.port, "/events?limit=2"));
            assertTrue(legacy.isArray(), "legacy stays a raw list");
            assertEquals(2, legacy.size());
        }
    }

    private static List<String> ids(JsonNode dataArray) {
        return java.util.stream.StreamSupport.stream(dataArray.spliterator(), false)
                .map(n -> n.get("eventId").asText()).toList();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
