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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Stage-5 {@code SpaceRoutes} CRUD over real HTTP: create + boot a space without restart, list it, confirm the
 * per-space seam then resolves it, and delete it (deregister-only vs {@code ?purge=true} file removal). Drives a
 * multi-space ControlApi built from an initially-empty {@code -Dspaces.root} container.
 */
class ControlApiSpacesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();   // drop the booted spaces' space-labelled series (shared process-wide registry)
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);   // empty container, but CRUD-capable (root remembered)
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    @Test
    void createListSeamAndDeleteSpacesOverHttp(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(0, json(send(c.port, "GET", "/spaces", null)).size(), "starts empty");
            // capability probe: the discover runtime is CRUD-capable even with no spaces yet
            assertTrue(json(send(c.port, "GET", "/spaces/_meta", null)).get("multiSpace").asBoolean(),
                    "discover mode advertises multiSpace=true");

            // ── create + boot a space ──
            HttpResponse<String> created = send(c.port, "POST", "/spaces",
                    "{\"id\":\"acme\",\"display_name\":\"ACME Corp\",\"description\":\"the acme space\"}");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("acme", json(created).get("id").asText());
            assertEquals("ACME Corp", json(created).get("displayName").asText());
            assertTrue(Files.isDirectory(root.resolve("acme").resolve("config")), "space dir minted on disk");

            // listed, and the per-space seam now resolves it (empty pipeline list, not a 404)
            JsonNode list = json(send(c.port, "GET", "/spaces", null));
            assertTrue(list.isArray() && list.size() == 1 && "acme".equals(list.get(0).get("id").asText()));
            HttpResponse<String> pipes = send(c.port, "GET", "/spaces/acme/runs", null);
            assertEquals(200, pipes.statusCode());
            assertEquals(0, json(pipes).size(), "fresh space hosts no pipelines yet");

            // duplicate → 409; invalid id → 400
            assertEquals(409, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(400, send(c.port, "POST", "/spaces", "{\"id\":\"Bad Id\"}").statusCode());

            // ── delete without purge: deregistered (seam 404s) but files remain ──
            HttpResponse<String> del = send(c.port, "DELETE", "/spaces/acme", null);
            assertEquals(200, del.statusCode(), del.body());
            assertFalse(json(del).get("purged").asBoolean());
            assertEquals(0, json(send(c.port, "GET", "/spaces", null)).size());
            assertEquals(404, send(c.port, "GET", "/spaces/acme/runs", null).statusCode(), "deregistered → seam 404");
            assertTrue(Files.isDirectory(root.resolve("acme")), "files kept when not purging");

            // deleting an unknown space → 404
            assertEquals(404, send(c.port, "DELETE", "/spaces/ghost", null).statusCode());

            // ── a second space, deleted WITH purge: its directory tree is removed ──
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
            assertTrue(Files.isDirectory(root.resolve("beta")));
            assertEquals(200, send(c.port, "DELETE", "/spaces/beta?purge=true", null).statusCode());
            assertFalse(Files.exists(root.resolve("beta")), "purge removed the space directory");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
