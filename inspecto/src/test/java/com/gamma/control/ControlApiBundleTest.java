package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Metadata Bundle v2 backend (SPC-4) over real HTTP: export (real content + contentHash), the
 * read-only preview fit-check (new/unchanged/drifted + requires satisfied/missing), import apply
 * (imported/overwritten/skipped/unchanged/failed, dependency order), and every gate — write-root
 * 503, unsupported-kind 422, malformed-envelope 422. One embedded engine per test, ephemeral port.
 */
class ControlApiBundleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    /** Seed a component of {@code type} with a single-field content, via the real CRUD route. */
    private void seed(int port, String type, String id, String field, String value) throws Exception {
        HttpResponse<String> r = send(port, "POST", "/components/" + type,
                "{\"id\":\"" + id + "\",\"" + field + "\":\"" + value + "\"}");
        assertEquals(200, r.statusCode(), r.body());
    }

    @Test
    void exportsRealContentWithHashAndReportsMissing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            seed(c.port, "dataset", "sales", "title", "Sales");
            seed(c.port, "widget", "sales_bar", "kind", "bar");

            JsonNode out = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"dataset\",\"id\":\"sales\"},"
                    + "{\"kind\":\"widget\",\"id\":\"sales_bar\"},"
                    + "{\"kind\":\"dataset\",\"id\":\"ghost\"}]}"));

            JsonNode bundle = out.get("bundle");
            assertEquals("inspecto-metadata-bundle", bundle.get("format").asText());
            assertEquals(2, bundle.get("version").asInt());
            assertEquals(2, bundle.get("items").size(), "only the two existing items travel");
            JsonNode item0 = bundle.get("items").get(0);
            assertEquals("Sales", item0.get("content").get("title").asText());
            assertTrue(item0.get("provenance").get("contentHash").asText().matches("sha256:[0-9a-f]{64}"));
            assertEquals(1, out.get("missing").size());
            assertEquals("ghost", out.get("missing").get(0).get("id").asText());
        }
    }

    @Test
    void exportRejectsUnsupportedKindAndEmptySelection(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(422, send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"connection\",\"id\":\"pg\"}]}").statusCode());
            assertEquals(422, send(c.port, "POST", "/bundle/export", "{\"items\":[]}").statusCode());
        }
    }

    // ── referential-integrity import gate (System Maintenance MNT-16) ───────────────

    @Test
    void importRejectsABundleThatIntroducesBrokenReferences(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String bad = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"widget\",\"id\":\"lonely\",\"content\":{\"vizType\":\"bar\",\"datasetId\":\"ghost_ds\"}}]}";
            HttpResponse<String> r = send(c.port, "POST", "/bundle/import", bad);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ghost_ds"), "the finding names the broken ref: " + r.body());
            assertEquals(404, send(c.port, "GET", "/components/widget/lonely", null).statusCode(),
                    "fail-closed: nothing was written");
        }
    }

    @Test
    void importAcceptsABundleWhoseItemsResolveEachOther(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            // The widget's dataset travels IN the same bundle — the union resolves, so the gate passes.
            String good = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"widget\",\"id\":\"lonely\",\"content\":{\"vizType\":\"bar\",\"datasetId\":\"ghost_ds\"}},"
                    + "{\"kind\":\"dataset\",\"id\":\"ghost_ds\",\"content\":{\"title\":\"Ghost\"}}]}";
            JsonNode out = json(send(c.port, "POST", "/bundle/import", good));
            assertEquals(2, out.get("imported").asInt(), out.toString());
            assertEquals(0, out.get("failed").asInt(), out.toString());
        }
    }

    @Test
    void preExistingBrokenRefsNeverBlockAnUnrelatedImport(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            seed(c.port, "widget", "old_broken", "datasetId", "long_gone");   // broken ref already on disk
            String unrelated = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"dataset\",\"id\":\"newcomer\",\"content\":{\"title\":\"New\"}}]}";
            JsonNode out = json(send(c.port, "POST", "/bundle/import", unrelated));
            assertEquals(1, out.get("imported").asInt(),
                    "an old broken ref is the registry's problem, not this bundle's: " + out);
        }
    }

    @Test
    void previewClassifiesNewUnchangedDriftedAndRequires(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            seed(c.port, "dataset", "sales", "title", "Sales");
            // Export gives a bundle whose item hash matches the stored component exactly.
            JsonNode exported = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"dataset\",\"id\":\"sales\"}]}")).get("bundle");

            // 1) identical bundle → unchanged
            JsonNode p1 = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(exported)));
            assertEquals("unchanged", p1.get("items").get(0).get("status").asText());

            // 2) mutate the item content → drifted (hash no longer matches the target)
            ObjectNode drift = (ObjectNode) exported.deepCopy();
            ((ObjectNode) drift.get("items").get(0).get("content")).put("title", "Renamed");
            JsonNode p2 = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(drift)));
            assertEquals("drifted", p2.get("items").get(0).get("status").asText());

            // 3) an item that does not exist on the target → new
            ObjectNode fresh = (ObjectNode) exported.deepCopy();
            ((ObjectNode) fresh.get("items").get(0)).put("id", "brand_new");
            JsonNode p3 = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(fresh)));
            assertEquals("new", p3.get("items").get(0).get("status").asText());

            // requires: existing dataset satisfied, missing one flagged
            ObjectNode withReq = (ObjectNode) exported.deepCopy();
            withReq.putArray("requires")
                    .add(JSON.createObjectNode().put("kind", "dataset").put("id", "sales"))
                    .add(JSON.createObjectNode().put("kind", "dataset").put("id", "absent"));
            JsonNode p4 = json(send(c.port, "POST", "/bundle/preview", JSON.writeValueAsString(withReq)));
            assertEquals("satisfied", p4.get("requires").get(0).get("status").asText());
            assertEquals("missing", p4.get("requires").get(1).get("status").asText());
        }
    }

    @Test
    void importAppliesOverwritesSkipsAndIsIdempotent(@TempDir Path source, @TempDir Path target) throws Exception {
        String bundle;
        try (Ctx src = open(source, source.resolve("wr"))) {
            seed(src.port, "dataset", "sales", "title", "Sales");
            seed(src.port, "widget", "sales_bar", "kind", "bar");
            bundle = JSON.writeValueAsString(json(send(src.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"widget\",\"id\":\"sales_bar\"},"
                    + "{\"kind\":\"dataset\",\"id\":\"sales\"}]}")).get("bundle"));
        }
        try (Ctx tgt = open(target, target.resolve("wr"))) {
            // fresh target → both imported; dependency order means dataset applies before the widget
            JsonNode r1 = json(send(tgt.port, "POST", "/bundle/import", bundle));
            assertEquals(2, r1.get("imported").asInt(), r1.toString());
            assertEquals("dataset", r1.get("results").get(0).get("kind").asText(), "referenced kinds first");
            assertEquals(200, send(tgt.port, "GET", "/components/dataset/sales", null).statusCode());

            // re-import identical bundle → idempotent (both unchanged, nothing written)
            JsonNode r2 = json(send(tgt.port, "POST", "/bundle/import", bundle));
            assertEquals(2, r2.get("unchanged").asInt(), r2.toString());
            assertEquals(0, r2.get("imported").asInt());

            // overwrite semantics on a single, independent dataset "kpi" (hand-written bundles):
            assertEquals(1, json(send(tgt.port, "POST", "/bundle/import", bundleOf("dataset", "kpi", "{\"title\":\"A\"}")))
                    .get("imported").asInt(), "first import");
            // differing content + explicit overwrite → overwritten
            String ow = "{\"bundle\":" + bundleOf("dataset", "kpi", "{\"title\":\"B\"}")
                    + ",\"actions\":{\"dataset/kpi\":\"overwrite\"}}";
            assertEquals(1, json(send(tgt.port, "POST", "/bundle/import", ow)).get("overwritten").asInt(), "explicit overwrite");
            // differing content, no action → defaults to skip (existing is preserved)
            assertEquals(1, json(send(tgt.port, "POST", "/bundle/import", bundleOf("dataset", "kpi", "{\"title\":\"C\"}")))
                    .get("skipped").asInt(), "differing but no overwrite → skip");
            // identical content → unchanged regardless (nothing to write)
            assertEquals(1, json(send(tgt.port, "POST", "/bundle/import", bundleOf("dataset", "kpi", "{\"title\":\"B\"}")))
                    .get("unchanged").asInt(), "identical to current → unchanged");
        }
    }

    /** A minimal single-item v2 bundle for import assertions. */
    private static String bundleOf(String kind, String id, String contentJson) {
        return "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"2026-07-07T00:00:00Z\","
                + "\"sourceSpace\":null,\"items\":[{\"kind\":\"" + kind + "\",\"id\":\"" + id + "\","
                + "\"content\":" + contentJson + "}]}";
    }

    @Test
    void importReportsUnsupportedKindAndBadItemWithoutAborting(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String bundle = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,"
                    + "\"exportedAt\":\"2026-07-07T00:00:00Z\",\"sourceSpace\":null,\"items\":["
                    + "{\"kind\":\"dataset\",\"id\":\"ok\",\"content\":{\"title\":\"OK\"}},"
                    + "{\"kind\":\"connection\",\"id\":\"pg\",\"content\":{\"host\":\"h\"}},"      // unsupported
                    + "{\"kind\":\"widget\",\"id\":\"nocontent\"}"                                  // missing content
                    + "]}";
            JsonNode r = json(send(c.port, "POST", "/bundle/import", bundle));
            assertEquals(1, r.get("imported").asInt(), r.toString());
            assertEquals(1, r.get("skipped").asInt(), "unsupported kind skipped");
            assertEquals(1, r.get("failed").asInt(), "missing content failed");
            assertEquals(200, send(c.port, "GET", "/components/dataset/ok", null).statusCode(),
                    "the good item still applied despite the two bad ones");
        }
    }

    @Test
    void importGatedOnWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {   // no write root
            String bundle = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"x\","
                    + "\"sourceSpace\":null,\"items\":[{\"kind\":\"dataset\",\"id\":\"d\",\"content\":{}}]}";
            assertEquals(503, send(c.port, "POST", "/bundle/import", bundle).statusCode());
        }
    }

    @Test
    void importValidatesEnvelope(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(422, send(c.port, "POST", "/bundle/import",
                    "{\"format\":\"nope\",\"version\":2,\"items\":[{\"kind\":\"dataset\",\"id\":\"d\",\"content\":{}}]}").statusCode());
            assertEquals(422, send(c.port, "POST", "/bundle/import",
                    "{\"format\":\"inspecto-metadata-bundle\",\"version\":3,\"items\":[{\"kind\":\"dataset\",\"id\":\"d\",\"content\":{}}]}").statusCode());
            assertEquals(422, send(c.port, "POST", "/bundle/import",
                    "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":[]}").statusCode());
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
