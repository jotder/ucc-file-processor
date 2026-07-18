package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.CollectorService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code NavRoutes} per-space Menu tree over real HTTP: the empty-tree default before any save, a PUT
 * round-trip that persists {@code nav-menus.toon} in the space's config tree (junk fields stripped by the
 * canonicalizing walk), per-space isolation via the {@code /spaces/{id}/nav/menus} seam, the 422
 * structural gates, and the 503 writes-disabled gate (legacy mode, no {@code -Dassist.write.root}).
 */
class ControlApiNavMenusTest {

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

    private static final String TREE = """
            {"space":"acme","version":1,"nodes":[
              {"id":"g1","title":"Revenue","icon":"heroicons_outline:chart-bar","children":[
                {"id":"l1","title":"Top usages","binding":{"kind":"widget","componentId":"w_top_usages"},"junk":"stripme"}
              ]},
              {"id":"l2","title":"Fraud overview","binding":{"kind":"dashboard","componentId":"d_fms"}}
            ]}""";

    @Test
    void menuTreeRoundTripsAndIsolatesPerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // default before any save — an empty tree stamped with the bound space
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/nav/menus", null));
            assertEquals("acme", def.get("space").asText());
            assertEquals(1, def.get("version").asInt());
            assertTrue(def.get("nodes").isArray() && def.get("nodes").isEmpty());

            // PUT round-trip; the canonicalizing walk keeps known fields and strips junk
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/nav/menus", TREE);
            assertEquals(200, put.statusCode(), put.body());
            JsonNode nodes = json(put).get("nodes");
            assertEquals(2, nodes.size());
            assertEquals("Revenue", nodes.get(0).get("title").asText());
            JsonNode leaf = nodes.get(0).get("children").get(0);
            assertEquals("w_top_usages", leaf.get("binding").get("componentId").asText());
            assertNull(leaf.get("junk"), "unknown fields are stripped, not persisted");

            // persisted on disk in the space's config tree (not a *_pipeline.toon suffix ⇒ config discovery ignores it)
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("nav-menus.toon")));
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/nav/menus", null));
            assertEquals("acme", got.get("space").asText());
            assertEquals("Fraud overview", got.get("nodes").get(1).get("title").asText());
            assertEquals("dashboard", got.get("nodes").get(1).get("binding").get("kind").asText());

            // per-space isolation: 'beta' still has the empty default
            JsonNode beta = json(send(c.port, "GET", "/spaces/beta/nav/menus", null));
            assertEquals("beta", beta.get("space").asText());
            assertTrue(beta.get("nodes").isEmpty());
        }
    }

    @Test
    void structurallyInvalidTreesAre422(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme/nav/menus";

            // wrong / missing version
            assertEquals(422, send(c.port, "PUT", base, "{\"version\":2,\"nodes\":[]}").statusCode());
            assertEquals(422, send(c.port, "PUT", base, "{\"nodes\":[]}").statusCode());
            // node missing a title
            assertEquals(422, send(c.port, "PUT", base,
                    "{\"version\":1,\"nodes\":[{\"id\":\"a\"}]}").statusCode());
            // duplicate node id (across nesting levels)
            assertEquals(422, send(c.port, "PUT", base,
                    "{\"version\":1,\"nodes\":[{\"id\":\"a\",\"title\":\"A\",\"children\":[{\"id\":\"a\",\"title\":\"B\"}]}]}")
                    .statusCode());
            // children and binding are mutually exclusive
            assertEquals(422, send(c.port, "PUT", base,
                    "{\"version\":1,\"nodes\":[{\"id\":\"a\",\"title\":\"A\",\"children\":[]," +
                            "\"binding\":{\"kind\":\"widget\",\"componentId\":\"w\"}}]}").statusCode());
            // binding missing componentId
            assertEquals(422, send(c.port, "PUT", base,
                    "{\"version\":1,\"nodes\":[{\"id\":\"a\",\"title\":\"A\",\"binding\":{\"kind\":\"widget\"}}]}")
                    .statusCode());

            // nothing was persisted by any rejected PUT
            assertFalse(Files.exists(root.resolve("acme").resolve("config").resolve("nav-menus.toon")));
        }
    }

    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.clearProperty("assist.write.root");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            // GET degrades to the empty default; PUT fails closed
            HttpResponse<String> get = send(api.port(), "GET", "/nav/menus", null);
            assertEquals(200, get.statusCode());
            assertTrue(json(get).get("nodes").isEmpty());
            assertEquals(503, send(api.port(), "PUT", "/nav/menus", "{\"version\":1,\"nodes\":[]}").statusCode(),
                    "no -Dassist.write.root ⇒ writes disabled");
        } finally {
            api.close();
            svc.close();
            MetricRegistry.global().reset();
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
