package com.gamma.control;

import com.gamma.service.SourceService;
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
 * Tests for the v4.1.0 UI-hosting additions to {@link ControlApi} (both pure-JDK, no new deps):
 * CORS ({@code -Dcontrol.cors}) and static SPA serving ({@code -Dui.dir}). These prove the operator
 * UI can be served from the same process while the JSON API — including its scoped auth and JSON
 * 404s — is unchanged.
 */
class ControlApiStaticAndCorsTest {

    private static final String TOKEN = "secret";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /**
     * Start a ControlApi over an empty service with the given system properties in effect during
     * construction (where the constructor reads them). Properties are cleared right after so they
     * never leak to other tests.
     */
    private Ctx open(String uiDir, String cors) throws Exception {
        if (uiDir != null) System.setProperty("ui.dir", uiDir);  else System.clearProperty("ui.dir");
        if (cors  != null) System.setProperty("control.cors", cors); else System.clearProperty("control.cors");
        try {
            SourceService svc = new SourceService(List.of(), 3600, 1);
            ControlApi api = new ControlApi(svc, 0, TOKEN);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("ui.dir");
            System.clearProperty("control.cors");
        }
    }

    /** Lay down a minimal built-SPA dir: index.html + a JS asset. */
    private static Path spaDir(Path dir) throws Exception {
        Path ui = dir.resolve("ui");
        Files.createDirectories(ui.resolve("assets"));
        Files.writeString(ui.resolve("index.html"), "<!doctype html><html><body>Inspecto UI</body></html>");
        Files.writeString(ui.resolve("assets").resolve("app.js"), "console.log('inspecto');");
        return ui;
    }

    private HttpResponse<String> send(int port, String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static String acao(HttpResponse<?> r) {
        return r.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
    }

    private static String ctype(HttpResponse<?> r) {
        return r.headers().firstValue("Content-Type").orElse("");
    }

    @Test
    void corsPreflightAnsweredWhenEnabled(@TempDir Path dir) throws Exception {
        try (Ctx c = open(null, "http://localhost:4200")) {
            HttpResponse<String> pre = send(c.port, "OPTIONS", "/pipelines", null);
            assertEquals(204, pre.statusCode(), "preflight short-circuits with 204");
            assertEquals("http://localhost:4200", acao(pre), "echoes the configured origin");
            // a real GET also carries the CORS header
            assertEquals("http://localhost:4200", acao(send(c.port, "GET", "/health", null)));
        }
    }

    @Test
    void noCorsHeadersWhenDisabled(@TempDir Path dir) throws Exception {
        try (Ctx c = open(null, null)) {
            HttpResponse<String> health = send(c.port, "GET", "/health", null);
            assertEquals(200, health.statusCode());
            assertNull(acao(health), "no CORS header when -Dcontrol.cors is unset");
            // OPTIONS is not short-circuited; it is just an unsupported method on a known path
            assertNotEquals(204, send(c.port, "OPTIONS", "/health", null).statusCode());
        }
    }

    @Test
    void knownRouteStillReturnsJson(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            HttpResponse<String> health = send(c.port, "GET", "/health", null);
            assertEquals(200, health.statusCode());
            assertTrue(ctype(health).startsWith("application/json"), "API route stays JSON even with a UI dir");
            assertTrue(health.body().contains("\"status\""));
        }
    }

    @Test
    void rootServesIndexHtml(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            HttpResponse<String> root = send(c.port, "GET", "/", null);
            assertEquals(200, root.statusCode());
            assertTrue(ctype(root).startsWith("text/html"));
            assertTrue(root.body().contains("Inspecto UI"), "index.html served at /");
        }
    }

    @Test
    void assetServedWithMimeType(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            HttpResponse<String> js = send(c.port, "GET", "/assets/app.js", null);
            assertEquals(200, js.statusCode());
            assertTrue(ctype(js).startsWith("text/javascript"));
            assertTrue(js.body().contains("inspecto"));
        }
    }

    @Test
    void extensionlessDeepLinkFallsBackToIndex(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            HttpResponse<String> deep = send(c.port, "GET", "/dashboard/pipelines", null);
            assertEquals(200, deep.statusCode(), "SPA deep link resolves to index.html");
            assertTrue(ctype(deep).startsWith("text/html"));
            assertTrue(deep.body().contains("Inspecto UI"));
        }
    }

    @Test
    void unknownApiPathStaysJson404NotIndex(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            // matches the /pipelines/{n}/commits route → handler 404 (no such pipeline), as JSON
            HttpResponse<String> r = send(c.port, "GET", "/pipelines/nope/commits", TOKEN);
            assertEquals(404, r.statusCode());
            assertTrue(ctype(r).startsWith("application/json"), "API 404 is JSON, not the SPA shell");
            assertFalse(r.body().contains("<html"), "must not serve index.html for an API path");
        }
    }

    @Test
    void staticIsPublicButApiStillRequiresToken(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            assertEquals(200, send(c.port, "GET", "/", null).statusCode(), "SPA shell loads without a token");
            assertEquals(401, send(c.port, "GET", "/pipelines", null).statusCode(),
                    "CONTROL route still 401s without a token");
            assertEquals(200, send(c.port, "GET", "/pipelines", TOKEN).statusCode(),
                    "and 200s with the control token");
        }
    }

    @Test
    void noStaticServingWhenUiDirUnset(@TempDir Path dir) throws Exception {
        try (Ctx c = open(null, null)) {
            // no -Dui.dir → an unmatched GET is a plain JSON 404 (legacy behaviour preserved)
            HttpResponse<String> r = send(c.port, "GET", "/", null);
            assertEquals(404, r.statusCode());
            assertTrue(ctype(r).startsWith("application/json"));
        }
    }
}
