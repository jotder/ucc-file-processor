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
 * {@code SettingsRoutes} per-space branding over real HTTP: defaults before any save, a PUT round-trip that
 * persists {@code branding.toon} in the space's config tree, blank-folds-to-null, per-space isolation via the
 * {@code /spaces/{id}/settings/branding} seam, and the over-large-logo 422 guard. Drives a discover-mode
 * ControlApi so each space has a real (writable) config root.
 */
class ControlApiSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
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
        return new Ctx(spaces, api, api.port(), root);
    }

    @Test
    void brandingRoundTripsAndIsolatesPerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // defaults before any save — all fields null (client falls back to the shipped defaults)
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/branding", null));
            assertTrue(def.get("logoDataUrl").isNull() && def.get("caption").isNull() && def.get("footerText").isNull());

            // PUT round-trip; blank footer folds to null
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/branding",
                    "{\"logoDataUrl\":\"data:image/png;base64,AAAA\",\"caption\":\"Chase the anomaly\",\"footerText\":\"  \"}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("Chase the anomaly", json(put).get("caption").asText());
            assertTrue(json(put).get("footerText").isNull(), "blank folds to null");

            // persisted on disk in the space's config tree (not a *_pipeline.toon suffix, so config discovery ignores it)
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("branding.toon")));
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/branding", null));
            assertEquals("data:image/png;base64,AAAA", got.get("logoDataUrl").asText());
            assertEquals("Chase the anomaly", got.get("caption").asText());

            // per-space isolation: 'beta' is untouched
            assertTrue(json(send(c.port, "GET", "/spaces/beta/settings/branding", null)).get("caption").isNull());

            // over-large logo → 422
            String bigLogo = "\"logoDataUrl\":\"" + "x".repeat(600 * 1024) + "\"";
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/branding", "{" + bigLogo + "}").statusCode());
        }
    }

    @Test
    void geoSettingsRoundTripAndIsolatePerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // default before any save — null (no self-hosted tile server)
            assertTrue(json(send(c.port, "GET", "/spaces/acme/settings/geo", null)).get("tileServerUrl").isNull());

            // PUT round-trip, persisted as geo.toon in the space's config tree
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/geo",
                    "{\"tileServerUrl\":\"http://tiles.example/{z}/{x}/{y}.png\"}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("http://tiles.example/{z}/{x}/{y}.png", json(put).get("tileServerUrl").asText());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("geo.toon")));
            assertEquals("http://tiles.example/{z}/{x}/{y}.png",
                    json(send(c.port, "GET", "/spaces/acme/settings/geo", null)).get("tileServerUrl").asText());

            // per-space isolation + blank-folds-to-null on save
            assertTrue(json(send(c.port, "GET", "/spaces/beta/settings/geo", null)).get("tileServerUrl").isNull());
            assertTrue(json(send(c.port, "PUT", "/spaces/acme/settings/geo", "{\"tileServerUrl\":\"  \"}"))
                    .get("tileServerUrl").isNull(), "blank folds to null");
        }
    }

    @Test
    void iconMapRoundTripsAndIsolatesPerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // default before any save — empty map
            assertEquals(0, json(send(c.port, "GET", "/spaces/acme/config/icon-map", null)).size(), "no rules yet");

            // PUT round-trip: a type entry + a category entry, persisted as icon-map.toon
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/config/icon-map",
                    "{\"parser.dsv\":{\"glyph\":\"table\",\"color\":\"#00aaff\"},\"PARSE\":{\"glyph\":\"filter\",\"color\":\"#ff8800\"}}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("table", json(put).get("parser.dsv").get("glyph").asText());
            assertEquals("#ff8800", json(put).get("PARSE").get("color").asText());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("icon-map.toon")));

            JsonNode got = json(send(c.port, "GET", "/spaces/acme/config/icon-map", null));
            assertEquals("filter", got.get("PARSE").get("glyph").asText());
            assertEquals("#00aaff", got.get("parser.dsv").get("color").asText());

            // per-space isolation: 'beta' is untouched
            assertEquals(0, json(send(c.port, "GET", "/spaces/beta/config/icon-map", null)).size());

            // a malformed entry (missing color) → 422
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/config/icon-map",
                    "{\"parser.json\":{\"glyph\":\"braces\"}}").statusCode());
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
