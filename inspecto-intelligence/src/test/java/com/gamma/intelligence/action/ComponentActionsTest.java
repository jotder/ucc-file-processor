package com.gamma.intelligence.action;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.gamma.control.ControlApi;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the P3 component act tools ({@link ComponentActions}) driving the loopback
 * {@link ControlPlaneClient} against a stub control plane. They assert the exact wire contract the
 * "no private backdoor" guarantee rests on — {@code GET}-then-{@code If-Match PUT} (existing),
 * {@code POST} create (new), and the {@code X-Agent-Session} attribution header on every write — plus
 * the safety hard-refuse and the read-only preview diff. (The real {@code actor=agent} audit on those
 * routes is proven separately by {@code ControlApiAgentInvokeTest} in the core module.)
 */
class ComponentActionsTest {

    private record Recorded(String method, String path, String ifMatch, String agentSession, String body) {}

    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile boolean componentExists = true;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        System.setProperty(ControlApi.LOCAL_BASE_URL_PROP, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopStub() {
        System.clearProperty(ControlApi.LOCAL_BASE_URL_PROP);
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String ifMatch = ex.getRequestHeaders().getFirst("If-Match");
        String agent = ex.getRequestHeaders().getFirst("X-Agent-Session");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(method, path, ifMatch, agent, body));

        int status;
        String etag;
        if (method.equals("GET")) {
            status = componentExists ? 200 : 404;
            etag = "\"sha256:current\"";
        } else if (method.equals("PUT")) {
            status = 200;
            etag = "\"sha256:updated\"";
        } else if (method.equals("POST") && path.endsWith("/restore")) {
            status = 200;
            etag = "\"sha256:restored\"";
        } else { // POST create
            status = 200;
            etag = "\"sha256:created\"";
        }
        ex.getResponseHeaders().set("ETag", etag);
        byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static ToolCall apply(String type, String id, Map<String, Object> config, String session) {
        return new ToolCall("component_apply", Map.of("type", type, "id", id, "config", config), new RunId(session));
    }

    @Test
    void applyToAnExistingComponentGetsThenPutsWithIfMatchAndAgentHeader() {
        componentExists = true;
        ToolResult r = ComponentActions.apply(new ControlPlaneClient(),
                apply("expectation", "amt-nonneg", Map.of("kind", "non_null", "column", "amt"), "sess-1"), "sess-1");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(false, v.get("created"));
        assertEquals("agent:sess-1", v.get("actor"));

        assertEquals(2, requests.size());
        assertEquals("GET", requests.get(0).method());
        Recorded put = requests.get(1);
        assertEquals("PUT", put.method());
        assertEquals("/components/expectation/amt-nonneg", put.path());
        assertEquals("\"sha256:current\"", put.ifMatch(), "PUT must carry the current ETag as If-Match");
        assertEquals("sess-1", put.agentSession(), "every write carries X-Agent-Session");
    }

    @Test
    void applyToANewComponentPostsCreateWithTheIdInTheBody() {
        componentExists = false;
        ToolResult r = ComponentActions.apply(new ControlPlaneClient(),
                apply("expectation", "brand-new", Map.of("kind", "non_null", "column", "x"), "sess-2"), "sess-2");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(true, v.get("created"));

        assertEquals(2, requests.size());
        assertEquals("GET", requests.get(0).method());
        Recorded post = requests.get(1);
        assertEquals("POST", post.method());
        assertEquals("/components/expectation", post.path());
        assertTrue(post.body().contains("brand-new"), "create body must carry the id");
        assertEquals("sess-2", post.agentSession());
    }

    @Test
    void applyRefusesAConfigThatFailsTheSafetyGateWithoutTouchingTheControlPlane() {
        // A path-jail escape in a pipeline config is a hard-fail safety finding.
        Map<String, Object> unsafe = Map.of("output",
                Map.of("ducklake", Map.of("data_path", "../../etc/evil")));
        ToolResult r = ComponentActions.apply(new ControlPlaneClient(),
                apply("pipeline", "evil", unsafe, "sess-3"), "sess-3");
        assertFalse(r.ok());
        assertTrue(r.error().toLowerCase().contains("safety"), () -> "error: " + r.error());
        assertTrue(requests.isEmpty(), "a refused apply must never reach the control plane");
    }

    @Test
    void rollbackPostsToTheRestoreRouteWithTheAgentHeader() {
        ToolResult r = ComponentActions.rollback(new ControlPlaneClient(),
                new ToolCall("component_rollback", Map.of("type", "expectation", "id", "amt-nonneg", "version", 2),
                        new RunId("sess-4")), "sess-4");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(2, v.get("version"));
        assertEquals(1, requests.size());
        Recorded post = requests.get(0);
        assertEquals("POST", post.method());
        assertEquals("/components/expectation/amt-nonneg/versions/2/restore", post.path());
        assertEquals("sess-4", post.agentSession());
    }

    @Test
    void anAbsentControlPlaneDegradesToAnHonestError() {
        System.clearProperty(ControlApi.LOCAL_BASE_URL_PROP);
        ToolResult r = ComponentActions.apply(new ControlPlaneClient(),
                apply("expectation", "x", Map.of("kind", "non_null"), "sess-5"), "sess-5");
        assertFalse(r.ok());
        assertTrue(r.error().toLowerCase().contains("not reachable"), () -> "error: " + r.error());
    }

    @Test
    void previewApplyShowsTheTopLevelDiffAndSafeFlag(@TempDir Path dir) throws Exception {
        ComponentStore store = new ComponentStore(dir.resolve("registry"));
        store.write("expectation", "amt-nonneg", new java.util.LinkedHashMap<>(Map.of("kind", "non_null", "column", "amt")));

        Map<String, Object> preview = ComponentActions.preview(store,
                apply("expectation", "amt-nonneg", Map.of("kind", "range", "column", "amt", "min", 0), "sess-6"));
        assertEquals("apply", preview.get("action"));
        assertEquals(false, preview.get("willCreate"));
        assertEquals(true, preview.get("safe"));
        @SuppressWarnings("unchecked")
        Map<String, Object> diff = (Map<String, Object>) preview.get("diff");
        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) diff.get("added");
        assertTrue(added.contains("min"), "the new 'min' field is an addition");
    }

    @Test
    void previewWithoutARegistryReturnsANote() {
        Map<String, Object> preview = ComponentActions.preview(null,
                apply("expectation", "x", Map.of("kind", "non_null"), "sess-7"));
        assertTrue(String.valueOf(preview.get("note")).contains("registry unavailable"));
    }
}
