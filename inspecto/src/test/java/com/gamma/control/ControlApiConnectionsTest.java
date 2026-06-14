package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.SourceService;
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
 *  reachability test against a local listening socket + the 404 / 401 gates. */
class ControlApiConnectionsTest {

    private static final String TOKEN = "secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, ConnectionProfile... profiles) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        SourceService svc = new SourceService(List.of(toon), 3600, 1);
        for (ConnectionProfile p : profiles) svc.registerConnection(p);
        ControlApi api = new ControlApi(svc, 0, TOKEN);
        api.start();
        return new Ctx(svc, api, api.port());
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
                JsonNode list = json(send(c.port, "GET", "/connections", TOKEN, null));
                assertTrue(list.isArray() && list.size() == 1);
                assertEquals("CDR_SFTP", list.get(0).get("id").asText());
                assertEquals("${ENV:CDR_PW}", list.get(0).get("password").asText());

                // by id: hit + miss
                assertEquals(200, send(c.port, "GET", "/connections/CDR_SFTP", TOKEN, null).statusCode());
                assertEquals(404, send(c.port, "GET", "/connections/none", TOKEN, null).statusCode());

                // test → reachable (the socket is listening), endpoint echoed
                JsonNode test = json(send(c.port, "POST", "/connections/CDR_SFTP/test", TOKEN, null));
                assertTrue(test.get("reachable").asBoolean(), test.toString());
                assertEquals("127.0.0.1:" + target, test.get("endpoint").asText());
                assertTrue(test.has("latencyMs"));

                // test of an unknown profile → 404
                assertEquals(404, send(c.port, "POST", "/connections/none/test", TOKEN, null).statusCode());
                // auth enforced
                assertEquals(401, send(c.port, "GET", "/connections", null, null).statusCode());
            }
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }
}
