package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /signals} over real HTTP: a failing Job run emits {@code job.run.started} (INFO) and
 * {@code job.run.failed} (CRITICAL) signals; the route reconstructs them from the event ledger and filters
 * by type glob, exact type, the {@code severity} floor and the {@code source} Ref (kind or {@code kind:id})
 * — plus the 400 gate on an unrecognised severity.
 */
class ControlApiSignalsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void queriesSignalsWithTypeAndSeverityFilters(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";

            // a maintenance job missing its required 'dir' param → the run throws → job.run.failed (CRITICAL)
            assertEquals(200, send(c.port, "POST", base + "/jobs",
                    "{\"name\":\"broken\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"cron\":\"0 3 * * *\"}").statusCode());
            send(c.port, "POST", base + "/jobs/broken/trigger", null);

            // signals arrive off the request thread — wait for the terminal one
            List<JsonNode> runSignals = pollForTerminalRunSignal(c.port, base);
            List<String> types = runSignals.stream().map(s -> s.get("type").asText()).toList();
            assertTrue(types.contains("job.run.started"), types.toString());
            assertTrue(types.contains("job.run.failed"), types.toString());

            // the severity floor filters in-store (the new param): CRITICAL keeps only the failure
            JsonNode critical = json(send(c.port, "GET", base + "/signals?severity=CRITICAL", null));
            assertTrue(critical.isArray() && !critical.isEmpty(), "a CRITICAL signal was emitted");
            boolean sawFailed = false;
            for (JsonNode s : critical) {
                assertEquals("critical", s.get("severity").asText(), "severity floor admits only CRITICAL (wire lowercase)");
                if ("job.run.failed".equals(s.get("type").asText())) sawFailed = true;
            }
            assertTrue(sawFailed, "the failed run's CRITICAL signal is present");

            // exact-type filter
            JsonNode failed = json(send(c.port, "GET", base + "/signals?type=job.run.failed", null));
            assertTrue(failed.isArray() && !failed.isEmpty());
            for (JsonNode s : failed) assertEquals("job.run.failed", s.get("type").asText());

            // source filter (the new param): the run signals are sourced Ref("job","broken") — filter by kind…
            JsonNode bySourceKind = json(send(c.port, "GET", base + "/signals?source=job", null));
            assertTrue(bySourceKind.isArray() && !bySourceKind.isEmpty(), "source=job returns the run signals");
            for (JsonNode s : bySourceKind)
                assertEquals("job", s.get("source").get("kind").asText(), "every result is job-sourced");
            // …and by the compact kind:id form
            JsonNode byCompact = json(send(c.port, "GET", base + "/signals?source=job:broken", null));
            assertTrue(byCompact.isArray() && !byCompact.isEmpty(), "source=job:broken matches the 'broken' job's signals");
            for (JsonNode s : byCompact) assertEquals("broken", s.get("source").get("id").asText());
            // a source matching nothing filters everything out
            JsonNode noSource = json(send(c.port, "GET", base + "/signals?source=pipeline", null));
            assertTrue(noSource.isArray() && noSource.isEmpty(), "a non-matching source returns no signals");

            // an unrecognised severity is a 400 (not a silent "everything")
            assertEquals(400, send(c.port, "GET", base + "/signals?severity=NOPE", null).statusCode());
        }
    }

    /** Poll {@code GET /signals?type=job.run.*} until the terminal (failed/completed) signal is recorded. */
    private List<JsonNode> pollForTerminalRunSignal(int port, String base) throws Exception {
        for (int i = 0; i < 150; i++) {
            JsonNode arr = json(send(port, "GET", base + "/signals?type=job.run.*", null));
            if (arr.isArray()) {
                List<JsonNode> out = new ArrayList<>();
                arr.forEach(out::add);
                boolean terminal = out.stream().map(s -> s.get("type").asText())
                        .anyMatch(t -> t.equals("job.run.failed") || t.equals("job.run.completed"));
                if (terminal) return out;
            }
            Thread.sleep(100);
        }
        fail("no terminal job.run.* signal within timeout");
        return null;   // unreachable
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
