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
 * Tests for the {@code frontend: text_regex} parsing frontend (docs/parsing-options-reference.md
 * §5/§6.5): each physical line is read intact (single-column {@code read_csv}), lines matching the
 * pattern are kept, and named capture groups become VARCHAR columns keyed by
 * {@code raw.fields[].selector} (= the group name).
 */
class TextRegexTest {

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: logv
              format: CSV
              fields[3]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                EVENT_DATE,"event_date",DATE
                AMOUNT,"amount",DOUBLE
            mapping:
              canonicalName: logv
              rawName: logv
              rules[3]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    // Line-regular log records: "<account> <date> <amount>" (no backslashes so the TOON string is plain).
    private static final String PARSING = """
            parsing:
              frontend: text_regex
              text_regex:
                pattern: "^(?P<account>[A-Z0-9]+) (?P<event_date>[0-9-]+) (?P<amount>[0-9.]+)$"
            """;

    private static final String DATA = """
            # billing extract v2 started
            A00001 2020-04-03 1234.5
            B00002 2020-04-04 9999.0
            === end of feed ===
            """;

    @Test
    void namedGroupsBecomeColumns(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "tr1", PARSING);
        assertNotNull(cfg.textRegex(), "text_regex frontend parsed");
        assertEquals(List.of("account", "event_date", "amount"), cfg.textRegex().groupNames());
        assertTrue(DuckDbCsvIngester.usesDuckDb(cfg), "text_regex is always native");

        File log = write(dir, "feed.log", DATA);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(log, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows(), "banner + footer lines don't match the pattern → dropped");
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
            assertEquals(List.of("2020-04-03", "2020-04-04"), col(conn, "raw_f0", "EVENT_DATE"));
            assertEquals(List.of("1234.5", "9999.0"), col(conn, "raw_f0", "AMOUNT"));
        }
    }

    @Test
    void javaStyleNamedGroupsAreNormalisedToRe2(@TempDir Path dir) throws Exception {
        String parsing = PARSING.replace("(?P<", "(?<");   // Java spelling in the config
        PipelineConfig cfg = load(dir, "tr2", parsing);
        assertEquals(List.of("account", "event_date", "amount"), cfg.textRegex().groupNames());
        assertTrue(cfg.textRegex().pattern().contains("(?P<account>"),
                "pattern normalised to the RE2 (?P<name>) spelling for DuckDB");

        File log = write(dir, "feed.log", DATA);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(log, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows());
        }
    }

    // ── validation ──────────────────────────────────────────────────────────────

    @Test
    void missingPatternFailsLoad(@TempDir Path dir) {
        String parsing = """
                parsing:
                  frontend: text_regex
                  text_regex:
                    record_split: "\\n"
                """;
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "nop", parsing));
        assertTrue(e.getMessage().contains("pattern is required"), e.getMessage());
    }

    @Test
    void patternWithoutNamedGroupFailsLoad(@TempDir Path dir) {
        String parsing = PARSING.replace(
                "^(?P<account>[A-Z0-9]+) (?P<event_date>[0-9-]+) (?P<amount>[0-9.]+)$",
                "^([A-Z0-9]+) ([0-9-]+)$");
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "nog", parsing));
        assertTrue(e.getMessage().contains("named"), e.getMessage());
    }

    @Test
    void nonCompilingPatternFailsLoad(@TempDir Path dir) {
        String parsing = PARSING.replace(
                "^(?P<account>[A-Z0-9]+) (?P<event_date>[0-9-]+) (?P<amount>[0-9.]+)$",
                "^(?P<account>[A-Z0-9+ oops");
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "bad", parsing));
        assertTrue(e.getMessage().contains("does not compile"), e.getMessage());
    }

    // ── block records (record_split) ────────────────────────────────────────────

    @Test
    void blankLineRecordSplitSpansMultipleLines(@TempDir Path dir) throws Exception {
        String parsing = """
                parsing:
                  frontend: text_regex
                  text_regex:
                    record_split: blank_line
                    pattern: "ACCOUNT: (?P<account>[A-Z0-9]+).*DATE: (?P<event_date>[0-9-]+).*AMOUNT: (?P<amount>[0-9.]+)"
                """;
        PipelineConfig cfg = load(dir, "blk", parsing);
        assertEquals("\n\n", cfg.textRegex().recordSplit(), "blank_line normalises to the literal delimiter");
        assertTrue(DuckDbCsvIngester.usesDuckDb(cfg), "text_regex is always native");

        String data = """
                ACCOUNT: A00001
                DATE: 2020-04-03
                AMOUNT: 1234.5

                ACCOUNT: B00002
                DATE: 2020-04-04
                AMOUNT: 9999.0
                """;
        File log = write(dir, "feed.log", data);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(log, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows(), "two blank-line-separated blocks, each spanning 3 lines");
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
            assertEquals(List.of("2020-04-03", "2020-04-04"), col(conn, "raw_f0", "EVENT_DATE"));
            assertEquals(List.of("1234.5", "9999.0"), col(conn, "raw_f0", "AMOUNT"));
        }
    }

    @Test
    void literalDelimiterRecordSplitIsAccepted(@TempDir Path dir) throws Exception {
        String parsing = """
                parsing:
                  frontend: text_regex
                  text_regex:
                    record_split: "---\\n"
                    pattern: "ACCOUNT: (?P<account>[A-Z0-9]+).*DATE: (?P<event_date>[0-9-]+).*AMOUNT: (?P<amount>[0-9.]+)"
                """;
        PipelineConfig cfg = load(dir, "dlm", parsing);
        assertEquals("---\n", cfg.textRegex().recordSplit(), "a literal non-alias string is used as-is");

        String data = """
                ACCOUNT: A00001
                DATE: 2020-04-03
                AMOUNT: 1234.5
                ---
                ACCOUNT: B00002
                DATE: 2020-04-04
                AMOUNT: 9999.0
                """;
        File log = write(dir, "feed.log", data);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(log, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows());
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
        }
    }

    @Test
    void selectorWithoutMatchingGroupFailsLoad(@TempDir Path dir) {
        // Drop the amount group so the schema's "amount" selector has no capture group.
        String parsing = PARSING.replace(" (?P<amount>[0-9.]+)", "");
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "sel", parsing));
        assertTrue(e.getMessage().contains("no matching text_regex capture group"), e.getMessage());
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
        return DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("tr_"));
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
                "name: TR_" + tag + "\n" +
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
                "  file_pattern: \"glob:**/*.log\"\n" +
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
