package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration + unit tests for the cross-engine store lineage stitch ({@code GET /lineage?store=}, §11).
 * Verifies the file→store bridge: ingest audit CSVs ({@code lineage} ⨝ {@code batches} on {@code batch_id},
 * filtered by {@code output_table}) surface as the {@code upstream} of a store.
 */
class ControlApiLineageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        SourceService svc = new SourceService(List.of(pipeline(dir)), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** A minimal single-schema pipeline with status/log dirs set, so its audit CSV paths resolve. */
    private Path pipeline(Path dir) throws Exception {
        PipelineConfigBatchTest.writePipeline(dir, "");          // creates dir/mini_schema.toon
        Path schema = dir.resolve("mini_schema.toon");
        String toon = """
            name: LINEAGE_ETL
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              temp: %1$s/temp
              errors: %1$s/errors
              status_dir: %1$s/status
              log_dir: %1$s/logs
            source:
              connector: db
              connection: warehouse
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%2$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, schema.toString().replace("\\", "/"));
        Path p = dir.resolve("lineage_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void lineageRequiresStoreParam(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(400, get(c.port, "/lineage").statusCode());
        }
    }

    @Test
    void storeLineageReadsIngestAuditCsvs(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var dirs = c.svc.configFor(c.svc.pipelines().get(0).name()).orElseThrow().dirs();
            assertNotNull(dirs.batchesFilePath(), "status_dir should resolve a batches CSV path");
            assertNotNull(dirs.lineageFilePath(), "status_dir should resolve a lineage CSV path");

            // Seed one batch that wrote to store 'events_raw', and a lineage row from subs.csv into it.
            Path batches = Path.of(dirs.batchesFilePath());
            Path lineage = Path.of(dirs.lineageFilePath());
            Files.createDirectories(batches.getParent());
            Files.writeString(batches, "batch_id,pipeline,schema_name,output_table\nb1,lineage_etl,mini,events_raw\n");
            Files.writeString(lineage, "batch_id,src_id,input_file,output_file,partition,row_count\n"
                    + "b1,0,subs.csv,/db/events_raw/day=2020-04-03/f.parquet,day=2020-04-03,1234\n");

            HttpResponse<String> r = get(c.port, "/lineage?store=events_raw");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = JSON.readTree(r.body());
            assertEquals("events_raw", body.get("store").asText());
            JsonNode up = body.get("upstream");
            assertTrue(up.isArray() && up.size() == 1, "one upstream row for events_raw: " + r.body());
            assertEquals("subs.csv", up.get(0).get("inputFile").asText());
            assertEquals(1234, up.get(0).get("rowCount").asLong());
            assertEquals("lineage_etl", up.get(0).get("pipeline").asText());
            assertTrue(body.get("downstream").isArray(), "downstream present (empty without -Dassist.write.root)");
        }
    }

    @Test
    void stitchUpstreamJoinsLineageToBatchOutputTable() {
        List<Map<String, String>> batches = List.of(
                Map.of("batch_id", "b1", "output_table", "events_raw"),
                Map.of("batch_id", "b2", "output_table", "other_store"));   // a batch to a different store → excluded
        List<Map<String, String>> lineage = List.of(
                Map.of("batch_id", "b1", "input_file", "subs.csv", "partition", "day=2020-04-03", "row_count", "1234"),
                Map.of("batch_id", "b2", "input_file", "ignored.csv", "partition", "day=2020-04-04", "row_count", "9"));

        var out = LineageRoutes.stitchUpstream("lineage_etl", "events_raw", batches, lineage);

        assertEquals(1, out.size(), "only the row whose batch wrote to events_raw");
        assertEquals("subs.csv", out.get(0).get("inputFile"));
        assertEquals(1234L, out.get(0).get("rowCount"));
        assertEquals("lineage_etl", out.get(0).get("pipeline"));
    }
}
