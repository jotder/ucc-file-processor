package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.AlertRule;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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
 * Integration tests for the alert-rule authoring routes ({@code POST/PUT/DELETE /alerts/rules[/{name}]})
 * over real HTTP: the author→save loop that persists a validated rule to {@code <name>_alert.toon} under
 * the {@code -Dassist.write.root} jail and arms it in the running {@link com.gamma.alert.AlertService}.
 * Covers the fail-closed gate ordering (writes disabled → invalid body 422 → unsafe name 422 → duplicate
 * 409 → unknown 404), that a written rule round-trips off disk, and that {@code GET /alerts/rules}
 * immediately reflects create/update/delete.
 *
 * <p>The path-jail (403) gate is defensive-only for this route: the rule name is the sole user-supplied
 * path component and {@link WriteGates#safeName} rejects any traversal/separator with 422 before the jail
 * is reached ({@link #unsafeNameRejected}), so 403 is unreachable here by construction.
 */
class ControlApiAlertRuleWriteTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server. {@code writeRoot==null} ⇒ writes disabled. */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        HttpRequest.BodyPublisher pub = body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body);
        return client.send(b.method(method, pub).build(), BodyHandlers.ofString());
    }

    private static String rule(String name, String comparator, double threshold) {
        return """
                {"name":"%s","metric":"error_rate","comparator":"%s","threshold":%s,
                 "window":"1h","severity":"WARNING"}""".formatted(name, comparator, threshold);
    }

    /** GET /alerts/rules → the set of armed rule names. */
    private List<String> ruleNames(int port) throws Exception {
        JsonNode arr = JSON.readTree(send(port, "GET", "/alerts/rules", null).body());
        return arr.findValuesAsText("name");
    }

    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = send(c.port, "POST", "/alerts/rules", rule("high-error", "gt", 0.05));
            assertEquals(503, r.statusCode(), "no -Dassist.write.root ⇒ writes disabled");
        }
    }

    @Test
    void createsArmsAndRoundTripsOffDisk(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertTrue(ruleNames(c.port).isEmpty(), "no rules armed at boot");

            HttpResponse<String> r = send(c.port, "POST", "/alerts/rules", rule("high-error", "gt", 0.05));
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("high-error", JSON.readTree(r.body()).get("name").asText());

            // Persisted under the write root with the *_alert.toon suffix ServiceBootstrap re-arms on boot.
            Path written = root.resolve("high-error_alert.toon");
            assertTrue(Files.exists(written), "rule persisted under the write root");
            AlertRule onDisk = AlertRule.load(written);
            assertEquals("high-error", onDisk.name());
            assertEquals("error_rate", onDisk.metric());
            assertEquals("gt", onDisk.comparator());

            // Armed in the running engine — GET reflects it immediately (no restart).
            assertEquals(List.of("high-error"), ruleNames(c.port));
        }
    }

    @Test
    void rejectsInvalidRuleAndWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "POST", "/alerts/rules", rule("bad", "between", 0.05));
            assertEquals(422, r.statusCode(), r.body());
            assertFalse(Files.exists(root.resolve("bad_alert.toon")), "nothing written on a rejected rule");
            assertTrue(ruleNames(c.port).isEmpty(), "nothing armed on a rejected rule");
        }
    }

    @Test
    void unsafeNameRejected(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // A valid rule body but a name unusable as a jailed filename → 422 (before the path jail).
            HttpResponse<String> r = send(c.port, "POST", "/alerts/rules", rule("../evil", "gt", 0.05));
            assertEquals(422, r.statusCode(), r.body());
        }
    }

    @Test
    void refusesDuplicateCreate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/alerts/rules", rule("dup", "gt", 0.05)).statusCode());
            assertEquals(409, send(c.port, "POST", "/alerts/rules", rule("dup", "lt", 0.9)).statusCode(),
                    "an existing rule is refused on create (use PUT)");
        }
    }

    @Test
    void updateRewritesArmedRuleAndDiskAnd404sUnknown(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, send(c.port, "PUT", "/alerts/rules/ghost", rule("ghost", "gt", 0.05)).statusCode(),
                    "updating an unknown rule is a 404");

            assertEquals(200, send(c.port, "POST", "/alerts/rules", rule("r1", "gt", 0.05)).statusCode());
            // Change the comparator; the name (path) is immutable and authoritative.
            HttpResponse<String> up = send(c.port, "PUT", "/alerts/rules/r1", rule("r1", "lt", 0.2));
            assertEquals(200, up.statusCode(), up.body());
            assertEquals("lt", JSON.readTree(up.body()).get("comparator").asText());

            AlertRule onDisk = AlertRule.load(root.resolve("r1_alert.toon"));
            assertEquals("lt", onDisk.comparator());
            assertEquals(0.2, onDisk.threshold(), 1e-9);
            assertEquals(List.of("r1"), ruleNames(c.port), "still exactly one armed rule (replace, not add)");
        }
    }

    @Test
    void deleteRemovesRuleAndFileAnd404sUnknown(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, send(c.port, "DELETE", "/alerts/rules/ghost", null).statusCode(),
                    "deleting an unknown rule is a 404");

            assertEquals(200, send(c.port, "POST", "/alerts/rules", rule("gone", "gt", 0.05)).statusCode());
            assertEquals(List.of("gone"), ruleNames(c.port));

            HttpResponse<String> del = send(c.port, "DELETE", "/alerts/rules/gone", null);
            assertEquals(200, del.statusCode(), del.body());
            assertEquals("gone", JSON.readTree(del.body()).get("deleted").asText());
            assertFalse(Files.exists(root.resolve("gone_alert.toon")), "file removed on delete");
            assertTrue(ruleNames(c.port).isEmpty(), "disarmed in the running engine");
        }
    }
}
