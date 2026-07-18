package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
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

/** Data Acquisition: the {@code /connections} routes over real HTTP — masked list/by-id + a live
 *  reachability test against a local listening socket + the 404 gates on update/delete of an unknown
 *  id. (The write-root 503 gate, success path, 409 duplicate-on-create and 409 in-use-on-delete are
 *  covered by {@link ControlApiCollectorsAndConnectionsTest}.) */
class ControlApiConnectionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, ConnectionProfile... profiles) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        for (ConnectionProfile p : profiles) svc.registerConnection(p);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void updateAndDeleteUnknownConnectionAre404(@TempDir Path dir, @TempDir Path root) throws Exception {
        System.setProperty("assist.write.root", root.toString());
        try (Ctx c = open(dir)) {
            String body = """
                {"id":"none","connector":"sftp","host":"h","port":22,"username":"svc","password":"${ENV:PW}"}
                """;
            assertEquals(404, send(c.port, "PUT", "/connections/none", body).statusCode(),
                    "updating an unknown connection id is a 404");
            assertEquals(404, send(c.port, "DELETE", "/connections/none", null).statusCode(),
                    "deleting an unknown connection id is a 404");
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    @Test
    void listByIdAndLiveReachabilityTest(@TempDir Path dir) throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {       // a real endpoint the test can reach
            int target = listening.getLocalPort();
            ConnectionProfile profile = ConnectionProfile.fromMap(Map.of(
                    "id", "CDR_SFTP", "connector", "sftp", "host", "127.0.0.1",
                    "port", String.valueOf(target), "password", "${ENV:CDR_PW}",
                    "options", Map.of("test_timeout_ms", "500")));

            try (Ctx c = open(dir, profile)) {
                // list — one profile, password shown as the ${…} ref (never a value)
                JsonNode list = json(send(c.port, "GET", "/connections", null));
                assertTrue(list.isArray() && list.size() == 1);
                assertEquals("CDR_SFTP", list.get(0).get("id").asText());
                assertEquals("${ENV:CDR_PW}", list.get(0).get("password").asText());

                // by id: hit + miss
                assertEquals(200, send(c.port, "GET", "/connections/CDR_SFTP", null).statusCode());
                assertEquals(404, send(c.port, "GET", "/connections/none", null).statusCode());

                // test → reachable (the socket is listening), endpoint echoed
                JsonNode test = json(send(c.port, "POST", "/connections/CDR_SFTP/test", null));
                assertTrue(test.get("reachable").asBoolean(), test.toString());
                assertEquals("127.0.0.1:" + target, test.get("endpoint").asText());
                assertTrue(test.has("latencyMs"));

                // test of an unknown profile → 404
                assertEquals(404, send(c.port, "POST", "/connections/none/test", null).statusCode());
            }
        }
    }

    @Test
    void unsavedProfileTestSelectsTheTargetHop(@TempDir Path dir) throws Exception {
        try (ServerSocket listening = new ServerSocket(0); Ctx c = open(dir)) {
            int target = listening.getLocalPort();
            String base = """
                {"id":"draft","connector":"sftp","host":"127.0.0.1","port":%d,
                 "options":{"test_timeout_ms":"500"}%s}
                """;

            // target=connection (and the default) probes the target host
            JsonNode conn = json(send(c.port, "POST", "/connections/test?target=connection",
                    base.formatted(target, "")));
            assertTrue(conn.get("reachable").asBoolean(), conn.toString());
            assertEquals("127.0.0.1:" + target, conn.get("endpoint").asText());

            // target=tunnel / target=proxy without the block → 422
            assertEquals(422, send(c.port, "POST", "/connections/test?target=tunnel",
                    base.formatted(target, "")).statusCode(), "no tunnel block ⇒ 422");
            assertEquals(422, send(c.port, "POST", "/connections/test?target=proxy",
                    base.formatted(target, "")).statusCode(), "no proxy block ⇒ 422");

            // target=proxy probes the proxy hop, not the target host
            String withProxy = base.formatted(1,      // deliberately dead target port — must NOT be probed
                    ",\"proxy\":{\"type\":\"SOCKS5\",\"host\":\"127.0.0.1\",\"port\":%d}".formatted(target));
            JsonNode proxy = json(send(c.port, "POST", "/connections/test?target=proxy", withProxy));
            assertTrue(proxy.get("reachable").asBoolean(), proxy.toString());
            assertEquals("127.0.0.1:" + target, proxy.get("endpoint").asText(), "the proxy hop is what was probed");

            // unknown target → 422
            assertEquals(422, send(c.port, "POST", "/connections/test?target=bogus",
                    base.formatted(target, "")).statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }
}
