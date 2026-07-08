package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-6 public dashboard sharing, every gate: disabled-by-default 503, issue + anonymous resolve,
 * tamper/expiry → indistinguishable 404, and the public query fenced to the dashboard's datasets.
 */
class ControlApiShareTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @AfterEach
    void clearSecret() {
        System.clearProperty("bi.share.secret");
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            SourceService svc = new SourceService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** A dashboard with one widget over the sales dataset (plus an unrelated, unreferenced dataset). */
    private void seed(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('US',5.0)) AS t(region,amount)", "2026-07-08T00:00:00Z"));
        ComponentStore reg = new ComponentStore(c.root.resolve("registry"));
        reg.write("dataset", "sales_ds", Map.of("view", "sales_view"));
        reg.write("dataset", "secret_ds", Map.of("view", "sales_view"));   // NOT referenced by the dashboard
        reg.write("widget", "sales_w", Map.of("kind", "bar", "datasetId", "sales_ds"));
        reg.write("dashboard", "exec_board", Map.of("title", "Exec", "widgets", List.of("sales_w")));
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void sharingIsDisabledWithoutASecret(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c);
            assertEquals(503, post(c.port, "/dashboards/exec_board/share", null).statusCode(),
                    "no -Dbi.share.secret → the whole surface is inert");
            assertEquals(404, get(c.port, "/public/dashboards/whatever").statusCode());
        }
    }

    @Test
    void issueResolveAndQueryWithinTheFence(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            HttpResponse<String> issued = post(c.port, "/dashboards/exec_board/share", "{\"ttl_hours\":1}");
            assertEquals(200, issued.statusCode(), issued.body());
            JsonNode data = JSON.readTree(issued.body()).has("data")
                    ? JSON.readTree(issued.body()).get("data") : JSON.readTree(issued.body());
            String token = data.get("token").asText();
            assertFalse(token.isBlank());

            // Anonymous resolve: dashboard + its widget come back read-only.
            HttpResponse<String> resolved = get(c.port, "/public/dashboards/" + token);
            assertEquals(200, resolved.statusCode(), resolved.body());
            JsonNode pub = JSON.readTree(resolved.body()).has("data")
                    ? JSON.readTree(resolved.body()).get("data") : JSON.readTree(resolved.body());
            assertEquals("exec_board", pub.get("dashboard").get("id").asText());
            assertEquals(1, pub.get("widgets").size());
            assertEquals("sales_w", pub.get("widgets").get(0).get("id").asText());

            // Public query over the referenced dataset works…
            HttpResponse<String> ok = post(c.port, "/public/dashboards/" + token + "/query",
                    "{\"dataset\":\"sales_ds\",\"measures\":[{\"agg\":\"sum\",\"field\":\"amount\"}]}");
            assertEquals(200, ok.statusCode(), ok.body());

            // …but the token is NOT a general data API: an unreferenced dataset is refused.
            assertEquals(403, post(c.port, "/public/dashboards/" + token + "/query",
                    "{\"dataset\":\"secret_ds\",\"measures\":[{\"agg\":\"count\"}]}").statusCode());
        }
    }

    @Test
    void tamperedAndUnknownTokensAreIndistinguishable404s(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            JsonNode issued = JSON.readTree(post(c.port, "/dashboards/exec_board/share", null).body());
            String token = (issued.has("data") ? issued.get("data") : issued).get("token").asText();
            assertFalse(token.isBlank(), "share must issue a token: " + issued);
            String tampered = token.substring(0, token.length() - 2) + "zz";
            assertEquals(404, get(c.port, "/public/dashboards/" + tampered).statusCode());
            assertEquals(404, get(c.port, "/public/dashboards/garbage.token").statusCode());
            assertEquals(404, post(c.port, "/public/dashboards/" + tampered + "/query",
                    "{\"dataset\":\"sales_ds\",\"measures\":[{\"agg\":\"count\"}]}").statusCode());
        }
    }

    @Test
    void unknownDashboardShareIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("bi.share.secret", "test-secret-0123456789");
        try (Ctx c = open(cfg, root)) {
            seed(c);
            assertEquals(404, post(c.port, "/dashboards/ghost/share", null).statusCode());
        }
    }
}
