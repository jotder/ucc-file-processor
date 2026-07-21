package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code frontend: json} parsing frontend (docs/parsing-options-reference.md §5/§6.4):
 * the unified {@code parsing:} block selects {@code read_ndjson}/{@code read_json}, each schema
 * field lands as a VARCHAR column keyed by {@code raw.fields[].selector} (= the top-level JSON key),
 * and the typing/mapping/partition backend is reused verbatim.
 */
class JsonParsingTest {

    // Schema: selectors are JSON keys, not column indices.
    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: ev
              format: CSV
              fields[3]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                EVENT_DATE,"event_date",DATE
                AMOUNT,"amount",DOUBLE
            mapping:
              canonicalName: ev
              rawName: ev
              rules[3]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    private static final String PARSING = """
            parsing:
              frontend: json
              json:
                format: newline
            """;

    @Test
    void ndjsonRowsLandAsVarcharColumns(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "j1", PARSING);
        assertNotNull(cfg.json(), "json frontend parsed from the parsing: block");
        assertEquals("newline", cfg.json().format());
        assertTrue(DuckDbCsvIngester.usesDuckDb(cfg), "json frontend is always native");

        File jsonl = write(dir, "ev.jsonl", """
                {"account":"A00001","event_date":"2020-04-03","amount":1234.5}
                {"account":"B00002","event_date":"2020-04-04","amount":9999.0}
                {this is not json at all
                """);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(jsonl, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows(), "malformed line dropped, two records land");
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
            assertEquals(List.of("2020-04-03", "2020-04-04"), col(conn, "raw_f0", "EVENT_DATE"));
            assertEquals(List.of("1234.5", "9999.0"), col(conn, "raw_f0", "AMOUNT"),
                    "JSON numbers cast to VARCHAR at ingest (typed later by DataTransformer)");
        }
    }

    @Test
    void arrayFormatReadsAJsonArrayDocument(@TempDir Path dir) throws Exception {
        String parsing = PARSING.replace("format: newline", "format: array");
        PipelineConfig cfg = load(dir, "j2", parsing);
        assertEquals("array", cfg.json().format());

        File json = write(dir, "ev.json", """
                [{"account":"A00001","event_date":"2020-04-03","amount":1},
                 {"account":"B00002","event_date":"2020-04-04","amount":2}]
                """);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows());
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
        }
    }

    @Test
    void missingKeysLandAsNull(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "j3", PARSING);
        File jsonl = write(dir, "ev.jsonl",
                "{\"account\":\"A00001\",\"event_date\":\"2020-04-03\"}\n");
        try (Connection conn = open()) {
            DuckDbCsvIngester.ingest(jsonl, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(1, col(conn, "raw_f0", "ACCOUNT_NUMBER").size());
            List<String> amount = col(conn, "raw_f0", "AMOUNT");
            assertNull(amount.get(0), "absent JSON key lands as NULL");
        }
    }

    // ── validation ──────────────────────────────────────────────────────────────

    @Test
    void unknownJsonFormatFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> load(dir, "bad", PARSING.replace("format: newline", "format: xmlish")));
        assertTrue(e.getMessage().contains("json.format"), e.getMessage());
    }

    @Test
    void nonRootRecordsPathFailsLoad(@TempDir Path dir) {
        String parsing = PARSING + "    records_path: \"$.data\"\n";
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "rp", parsing));
        assertTrue(e.getMessage().contains("records_path"), e.getMessage());
    }

    @Test
    void unknownFrontendFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> load(dir, "uf", "parsing:\n  frontend: xml\n"));
        assertTrue(e.getMessage().contains("Unknown parsing.frontend"), e.getMessage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static List<String> col(Connection conn, String table, String c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT \"" + c + "\" FROM \"" + table + "\" ORDER BY \"ACCOUNT_NUMBER\"")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Connection open() throws Exception {
        return DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("json_"));
    }

    private static File write(Path dir, String name, String content) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), content);
        return f;
    }

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    /** Build + load a pipeline with the given top-level {@code parsing:} block. */
    private static PipelineConfig load(Path dir, String tag, String parsingBlock) throws Exception {
        Path schema = dir.resolve("schema_" + tag + ".toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        String d = fwd(dir);
        String pipe =
                "name: JSON_" + tag + "\n" +
                "version: 1\n" +
                "dirs:\n" +
                "  poll: " + d + "/inbox\n" +
                "  database: " + d + "/db\n" +
                "  backup: " + d + "/backup\n" +
                "  temp: " + d + "/temp\n" +
                "  errors: " + d + "/errors\n" +
                "  quarantine: " + d + "/quarantine\n" +
                "  status_dir: " + d + "/status\n" +
                "output:\n" +
                "  format: PARQUET\n" +
                "processing:\n" +
                "  threads: 1\n" +
                "  file_pattern: \"glob:**/*.jsonl\"\n" +
                "  schema_file: " + fwd(schema) + "\n" +
                "  csv_settings:\n" +
                "    date_formats[1]: \"%Y-%m-%d\"\n" +
                "    timestamp_formats[1]: \"%Y-%m-%d\"\n" +
                parsingBlock;
        Path p = dir.resolve("pipe_" + tag + ".toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
