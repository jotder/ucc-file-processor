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
 * The shipped space-template gallery over real HTTP: {@code GET /spaces/templates} lists
 * {@code _templates/<id>/template.toon} entries (empty when none ship), and {@code POST /spaces} with a
 * {@code template} seeds the new space from the template's config tree with every {@code ${SPACE}} token
 * rewritten to the new id — the booted space discovers the copied pipeline like any authored one.
 */
class ControlApiSpaceTemplatesTest {

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

    /** A minimal on-disk template: metadata + one ${SPACE}-addressed pipeline + a registry dataset. */
    private void seedTemplate(Path root) throws Exception {
        Path tpl = root.resolve("_templates").resolve("starter");
        Files.createDirectories(tpl.resolve("config").resolve("orders"));
        Files.createDirectories(tpl.resolve("config").resolve("registry").resolve("datasets"));
        Files.writeString(tpl.resolve("template.toon"), """
                name: Starter
                tagline: "A tiny starter feed"
                description: "One pipeline + one dataset."
                icon: heroicons_outline:cube
                contents[2]: "Pipeline", "Dataset"
                """);
        Files.writeString(tpl.resolve("config").resolve("orders").resolve("orders_pipeline.toon"), """
                name: orders
                active: false
                version: 1
                dirs:
                  poll:       spaces/${SPACE}/data/inbox/orders
                  database:   spaces/${SPACE}/data/orders/database
                  backup:     spaces/${SPACE}/data/orders/backup
                  temp:       spaces/${SPACE}/data/orders/temp
                  errors:     spaces/${SPACE}/data/orders/errors
                  quarantine: spaces/${SPACE}/data/orders/quarantine
                  markers:    spaces/${SPACE}/data/orders/markers
                  status_dir: spaces/${SPACE}/data/orders/status
                  log_dir:    spaces/${SPACE}/data/orders/logs
                output:
                  format: PARQUET
                  compression: snappy
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  csv_settings:
                    delimiter: ","
                """);
        Files.writeString(tpl.resolve("config").resolve("registry").resolve("datasets").resolve("orders_dataset.toon"),
                "physicalRef: orders/database\n");
    }

    @Test
    void galleryListsShippedTemplatesAndIsEmptyWithoutAny(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode empty = json(send(c.port, "GET", "/spaces/templates", null));
            assertTrue(empty.isArray() && empty.isEmpty(), "no _templates dir -> empty gallery");
        }
        seedTemplate(root);
        try (Ctx c = open(root)) {
            JsonNode list = json(send(c.port, "GET", "/spaces/templates", null));
            assertEquals(1, list.size());
            JsonNode t = list.get(0);
            assertEquals("starter", t.get("id").asText());
            assertEquals("Starter", t.get("name").asText());
            assertEquals("A tiny starter feed", t.get("tagline").asText());
            assertEquals(2, t.get("contents").size());
        }
    }

    @Test
    void createFromTemplateRewritesSpaceTokensAndBoots(@TempDir Path root) throws Exception {
        seedTemplate(root);
        try (Ctx c = open(root)) {
            HttpResponse<String> created = send(c.port, "POST", "/spaces",
                    "{\"id\":\"acme\",\"template\":\"starter\"}");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("Starter", json(created).get("displayName").asText(), "display name defaults from the template");

            // config copied with ${SPACE} rewritten to the new id
            String pipeline = Files.readString(
                    root.resolve("acme").resolve("config").resolve("orders").resolve("orders_pipeline.toon"));
            assertTrue(pipeline.contains("spaces/acme/data/inbox/orders"), pipeline);
            assertFalse(pipeline.contains("${SPACE}"), "every token rewritten");
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("registry")
                    .resolve("datasets").resolve("orders_dataset.toon")));

            // the seeded space boots and registers like any other (config-internal spaces/<id>/… paths
            // resolve against the server's CWD, so the copied pipeline itself only loads in a real
            // deployment layout — the live smoke covers that; here the space must list + scope cleanly)
            HttpResponse<String> spaces = send(c.port, "GET", "/spaces", null);
            assertTrue(spaces.body().contains("\"acme\""), spaces.body());
            assertEquals(200, send(c.port, "GET", "/spaces/acme/pipelines", null).statusCode());

            // unknown template -> 400; duplicate id -> 409
            assertEquals(400, send(c.port, "POST", "/spaces", "{\"id\":\"x1\",\"template\":\"nope\"}").statusCode());
            assertEquals(409, send(c.port, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"starter\"}").statusCode());
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
