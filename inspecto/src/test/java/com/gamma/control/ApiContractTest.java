package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@code docs/api/openapi-v1.json} (worklog W2,
 * docs/superpower/api-contract-design.md §9 "offline testability"): the OpenAPI document, the
 * canonical examples under {@code docs/api/examples/}, the {@link ErrorCodes} catalog and the live
 * {@code /api/v1} surface must all agree — this class fails when any of them drifts.
 */
class ApiContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** Example file → the component schema it must satisfy (README authoring step 3). */
    private static final Map<String, String> EXAMPLES = Map.of(
            "envelope-health.json", "Envelope",
            "error-not-found.json", "ErrorResponse",
            "error-write-root-503.json", "ErrorResponse",
            "error-validation-422.json", "ErrorResponse",
            "error-stale-version-409.json", "ErrorResponse",
            "signal.json", "Signal");

    // ── contract loading ─────────────────────────────────────────────────────────

    /** The repo's docs/api dir, found by walking up from the module CWD (works from repo root too). */
    private static Path docsApi() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs").resolve("api");
            if (Files.isRegularFile(candidate.resolve("openapi-v1.json"))) return candidate;
        }
        throw new IllegalStateException("docs/api/openapi-v1.json not found above " + Path.of("").toAbsolutePath());
    }

    private static JsonNode contract() throws Exception {
        return JSON.readTree(docsApi().resolve("openapi-v1.json").toFile());
    }

    // ── doc ↔ code ───────────────────────────────────────────────────────────────

    @Test
    void errorCodeCatalogMatchesContractEnum() throws Exception {
        Set<String> code = new TreeSet<>();
        for (Field f : ErrorCodes.class.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                code.add((String) f.get(null));

        Set<String> doc = new TreeSet<>();
        contract().path("components").path("schemas").path("ErrorCode").path("enum")
                .forEach(n -> doc.add(n.asText()));

        assertEquals(code, doc, "ErrorCodes.java and the contract's ErrorCode enum must stay in lockstep");
    }

    // ── doc ↔ examples ───────────────────────────────────────────────────────────

    @Test
    void examplesSatisfyTheirDeclaredSchemas() throws Exception {
        JsonNode contract = contract();
        Path examples = docsApi().resolve("examples");
        for (Map.Entry<String, String> e : EXAMPLES.entrySet()) {
            JsonNode instance = JSON.readTree(examples.resolve(e.getKey()).toFile());
            assertSatisfies(contract, e.getValue(), instance, e.getKey());
        }
        // Every example file present is registered (a new shape must join the manifest above).
        try (var files = Files.list(examples)) {
            Set<String> onDisk = new HashSet<>();
            files.forEach(p -> onDisk.add(p.getFileName().toString()));
            assertEquals(EXAMPLES.keySet(), onDisk, "examples/ and the EXAMPLES manifest must match");
        }
    }

    // ── doc ↔ live surface ───────────────────────────────────────────────────────

    @Test
    void probedOperationsMatchLiveServer(@TempDir Path cfg) throws Exception {
        JsonNode contract = contract();
        List<String> probed = new ArrayList<>();
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.clearProperty("assist.write.root");
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
             ControlApi api = new ControlApi(svc, 0)) {
            api.start();
            var paths = contract.path("paths");
            for (var pathIt = paths.fields(); pathIt.hasNext(); ) {
                var pathEntry = pathIt.next();
                for (var opIt = pathEntry.getValue().fields(); opIt.hasNext(); ) {
                    var opEntry = opIt.next();
                    JsonNode probe = opEntry.getValue().get("x-probe");
                    if (probe == null) continue;
                    assertEquals("get", opEntry.getKey(), "x-probe is only supported on GET operations");

                    String probePath = probe.get("path").asText();
                    int expected = probe.get("status").asInt();
                    HttpResponse<String> r = client.send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + probePath))
                                    .GET().build(),
                            BodyHandlers.ofString());
                    assertEquals(expected, r.statusCode(),
                            "probe " + probePath + " (documented on " + pathEntry.getKey() + ")");

                    JsonNode body = JSON.readTree(r.body());
                    assertSatisfies(contract, expected < 400 ? "Envelope" : "ErrorResponse", body,
                            "live " + probePath);
                    probed.add(probePath);
                }
            }
        }
        assertFalse(probed.isEmpty(), "the contract should declare at least one x-probe");
    }

    // ── a minimal structural checker (required-tree + enums; no schema-validator dependency) ──

    /** Assert {@code instance} satisfies the named component schema's required/enum tree. */
    private static void assertSatisfies(JsonNode contract, String schemaName, JsonNode instance, String at) {
        JsonNode schema = contract.path("components").path("schemas").path(schemaName);
        assertFalse(schema.isMissingNode(), "contract has no schema '" + schemaName + "'");
        check(contract, schema, instance, at + " ~ " + schemaName, 0);
    }

    private static void check(JsonNode contract, JsonNode schema, JsonNode instance, String at, int depth) {
        if (depth > 6 || schema == null || schema.isMissingNode() || instance == null) return;
        if (schema.has("$ref")) {
            check(contract, resolve(contract, schema.get("$ref").asText()), instance, at, depth + 1);
            return;
        }
        if (schema.has("enum") && instance.isTextual()) {
            Set<String> allowed = new HashSet<>();
            schema.get("enum").forEach(n -> allowed.add(n.asText()));
            assertTrue(allowed.contains(instance.asText()),
                    at + ": '" + instance.asText() + "' is not in the documented enum " + allowed);
        }
        if (instance.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null)
                for (JsonNode r : required)
                    assertTrue(instance.has(r.asText()), at + ": missing required field '" + r.asText() + "'");
            JsonNode props = schema.get("properties");
            if (props != null)
                for (var it = props.fields(); it.hasNext(); ) {
                    var prop = it.next();
                    JsonNode child = instance.get(prop.getKey());
                    if (child != null) check(contract, prop.getValue(), child, at + "." + prop.getKey(), depth + 1);
                }
        }
    }

    /** Resolve a local {@code #/components/…} JSON pointer within the contract document. */
    private static JsonNode resolve(JsonNode contract, String ref) {
        assertTrue(ref.startsWith("#/"), "only local $refs are supported: " + ref);
        JsonNode node = contract;
        for (String seg : ref.substring(2).split("/")) node = node.path(seg);
        assertFalse(node.isMissingNode(), "dangling $ref " + ref);
        return node;
    }
}
