package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Component registry CRUD (T19, §7.1) over real HTTP: create / list / get / update / delete
 * grammar/schema/transform/sink under {@code <write-root>/registry}, the write-root 503 gate, and the
 * unknown-type / not-found guards.
 */
class ControlApiComponentsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Open a service + API; when {@code writeRoot} is non-null, enable component writes jailed under it. */
    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);   // captures the write root at construction
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    @Test
    void crudLifecycleForAGrammarComponent(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            // create
            HttpResponse<String> created = send(c.port, "POST", "/components/grammar",
                    "{\"id\":\"pipe\",\"delimiter\":\"|\",\"has_header\":true}");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("grammar/pipe", json(created).get("ref").asText());
            assertEquals("|", json(created).get("content").get("delimiter").asText());
            // it lands on disk under registry/grammars/
            assertTrue(Files.exists(wr.resolve("registry/grammars/pipe.toon")));

            // duplicate → 409
            assertEquals(409, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\",\"delimiter\":\",\"}").statusCode());

            // list + get
            JsonNode list = json(send(c.port, "GET", "/components/grammar", null));
            assertEquals(1, list.size());
            assertEquals("pipe", list.get(0).get("name").asText());
            assertEquals("|", json(send(c.port, "GET", "/components/grammar/pipe", null)).get("content").get("delimiter").asText());

            // update (PUT) replaces content
            assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\";\"}").statusCode());
            assertEquals(";", json(send(c.port, "GET", "/components/grammar/pipe", null)).get("content").get("delimiter").asText());

            // update a missing one → 404
            assertEquals(404, send(c.port, "PUT", "/components/grammar/ghost", "{\"delimiter\":\",\"}").statusCode());

            // delete (no flow references it → allowed)
            assertEquals(200, send(c.port, "DELETE", "/components/grammar/pipe", null).statusCode());
            assertEquals(0, json(send(c.port, "GET", "/components/grammar", null)).size());
            assertEquals(404, send(c.port, "GET", "/components/grammar/pipe", null).statusCode());
        }
    }

    @Test
    void unknownTypeAndConnectionAreRejected(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(400, send(c.port, "GET", "/components/bogus", null).statusCode());
            // connection has its own secret-aware CRUD — not managed here
            assertEquals(400, send(c.port, "POST", "/components/connection", "{\"id\":\"x\"}").statusCode());
        }
    }

    @Test
    void transformPreviewRunsSampleThroughTheProductionShaper(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            // a transform.filter component
            assertEquals(200, send(c.port, "POST", "/components/transform",
                    "{\"id\":\"big-only\",\"type\":\"transform.filter\",\"where\":\"CAST(amt AS INT) >= 100\"}").statusCode());

            // dry-run it over sample rows
            HttpResponse<String> r = send(c.port, "POST", "/components/transform/big-only/test",
                    "{\"sampleRows\":[{\"id\":\"1\",\"amt\":\"150\"},{\"id\":\"2\",\"amt\":\"50\"}]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            JsonNode rels = body.get("relations");
            int data = 0, dropped = 0;
            for (JsonNode rel : rels) {
                if ("data".equals(rel.get("rel").asText())) data = rel.get("rowCount").asInt();
                if ("dropped".equals(rel.get("rel").asText())) dropped = rel.get("rowCount").asInt();
            }
            assertEquals(1, data, body.toString());        // id 1 (amt 150) kept
            assertEquals(1, dropped);                       // id 2 (amt 50) dropped

            // preview of a missing component → 404
            assertEquals(404, send(c.port, "POST", "/components/transform/ghost/test", "{\"sampleRows\":[]}").statusCode());
        }
    }

    @Test
    void grammarPreviewParsesRawSampleText(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/components/grammar",
                    "{\"id\":\"pipe\",\"delimiter\":\"|\",\"has_header\":true}").statusCode());

            HttpResponse<String> r = send(c.port, "POST", "/components/grammar/pipe/test",
                    "{\"sampleText\":\"a|b|c\\n1|2|3\\n4|5|6\\n\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            assertEquals(2, body.get("rowCount").asInt(), body.toString());
            assertEquals(0, body.get("rejectedRows").asInt());
            assertEquals("a", body.get("columns").get(0).asText());

            assertEquals(404, send(c.port, "POST", "/components/grammar/ghost/test", "{\"sampleText\":\"x\"}").statusCode());
        }
    }

    @Test
    void schemaPreviewSplitsDataAndRejected(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/components/schema",
                    "{\"id\":\"typed\",\"fields\":[{\"name\":\"id\",\"type\":\"integer\"},{\"name\":\"amt\",\"type\":\"double\"}]}").statusCode());

            HttpResponse<String> r = send(c.port, "POST", "/components/schema/typed/test",
                    "{\"sampleRows\":[{\"id\":\"1\",\"amt\":\"150\"},{\"id\":\"x\",\"amt\":\"50\"},{\"id\":\"3\",\"amt\":\"abc\"}]}");
            assertEquals(200, r.statusCode(), r.body());
            int data = 0, rejected = 0;
            for (JsonNode rel : json(r).get("relations")) {
                if ("data".equals(rel.get("rel").asText())) data = rel.get("rowCount").asInt();
                if ("rejected".equals(rel.get("rel").asText())) rejected = rel.get("rowCount").asInt();
            }
            assertEquals(1, data);
            assertEquals(2, rejected);
        }
    }

    @Test
    void sinkPreviewValidatesConfigAgainstSample(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/components/sink",
                    "{\"id\":\"out\",\"store\":\"results\",\"format\":\"parquet\",\"partitions\":[\"year\"]}").statusCode());

            HttpResponse<String> r = send(c.port, "POST", "/components/sink/out/test",
                    "{\"sampleRows\":[{\"id\":\"1\"},{\"id\":\"2\"}]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            assertEquals("results", body.get("store").asText());
            assertEquals(2, body.get("rowCount").asInt());
            assertEquals(1, body.get("warnings").size(), body.toString());   // missing partition column 'year'
        }
    }

    @Test
    void writesAreGatedOnTheWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {                       // no write root configured
            assertEquals(503, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\"}").statusCode());
            assertEquals(List.of(), JSON.readValue(send(c.port, "GET", "/components/grammar", null).body(), List.class));
        }
    }

    @Test
    void versionHistoryListsPriorCopiesAndRestores(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            // create → no history yet (nothing was overwritten)
            assertEquals(200, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\",\"delimiter\":\"|\"}").statusCode());
            assertEquals(0, json(send(c.port, "GET", "/components/grammar/pipe/versions", null)).size(), "no history on create");

            // two edits → two archived pre-edit copies
            assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\",\"}").statusCode());
            assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\";\"}").statusCode());

            JsonNode versions = json(send(c.port, "GET", "/components/grammar/pipe/versions", null));
            assertEquals(2, versions.size(), versions.toString());
            assertEquals(2, versions.get(0).get("version").asInt(), "newest first");
            assertEquals(",", versions.get(0).get("content").get("delimiter").asText());   // v2 archived the ',' edit
            assertEquals(1, versions.get(1).get("version").asInt());
            assertEquals("|", versions.get(1).get("content").get("delimiter").asText());    // v1 archived the original
            assertNotNull(versions.get(0).get("contentHash").asText());

            // current is the latest edit
            assertEquals(";", json(send(c.port, "GET", "/components/grammar/pipe", null)).get("content").get("delimiter").asText());

            // restore v1 (the original '|') → current reverts, and the outgoing ';' is archived as a new version
            HttpResponse<String> restored = send(c.port, "POST", "/components/grammar/pipe/versions/1/restore", "");
            assertEquals(200, restored.statusCode(), restored.body());
            assertEquals("|", json(restored).get("content").get("delimiter").asText());
            assertEquals("|", json(send(c.port, "GET", "/components/grammar/pipe", null)).get("content").get("delimiter").asText());
            assertEquals(3, json(send(c.port, "GET", "/components/grammar/pipe/versions", null)).size(),
                    "restore archives the outgoing copy");

            // a missing version → 404; a non-integer version → 400
            assertEquals(404, send(c.port, "POST", "/components/grammar/pipe/versions/99/restore", "").statusCode());
            assertEquals(400, send(c.port, "POST", "/components/grammar/pipe/versions/abc/restore", "").statusCode());
            // versions of a missing component → 404
            assertEquals(404, send(c.port, "GET", "/components/grammar/ghost/versions", null).statusCode());
        }
    }

    @Test
    void versionHistoryIsPrunedToTheKeepBound(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {   // default keep = 10
            assertEquals(200, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\",\"delimiter\":\"x0\"}").statusCode());
            for (int i = 1; i <= 13; i++)
                assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\"x" + i + "\"}").statusCode());
            JsonNode versions = json(send(c.port, "GET", "/components/grammar/pipe/versions", null));
            assertEquals(10, versions.size(), "pruned to the keep bound");
            assertEquals(13, versions.get(0).get("version").asInt(), "newest kept");
            assertEquals(4, versions.get(9).get("version").asInt(), "oldest kept = 13 - 10 + 1");
        }
    }

    @Test
    void versionHistoryIsInvisibleToTheRegistryScan(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            assertEquals(200, send(c.port, "POST", "/components/grammar", "{\"id\":\"pipe\",\"delimiter\":\"|\"}").statusCode());
            assertEquals(200, send(c.port, "PUT", "/components/grammar/pipe", "{\"delimiter\":\",\"}").statusCode());
            // the archived copy sits in registry/grammars/.history/, never scanned as a component
            assertTrue(Files.exists(wr.resolve("registry/grammars/.history/pipe.v1.toon")), "archived under .history/");
            JsonNode list = json(send(c.port, "GET", "/components/grammar", null));
            assertEquals(1, list.size(), "history copies do not appear as components");
            assertEquals("pipe", list.get(0).get("name").asText());
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
