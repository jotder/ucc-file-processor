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
 * Tests for the fixed-width text frontend (docs/superpowers/specs/2026-06-13-fixedwidth-frontend-design.md):
 * the {@code frontend: fixedwidth} grammar carves each physical line into positional VARCHAR columns
 * via {@code read_csv}(single column) + {@code substring}, reusing the delimited backend verbatim.
 */
class FixedWidthTest {

    // Schema: ACCOUNT_NUMBER(slice 0), EVENT_DATE(slice 1), AMOUNT(slice 2). Selector i = slice i.
    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: sub
              format: CSV
              fields[3]{name,selector,type}:
                ACCOUNT_NUMBER,"0",VARCHAR
                EVENT_DATE,"1",DATE
                AMOUNT,"2",DOUBLE
            mapping:
              canonicalName: sub
              rawName: sub
              rules[3]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    // Fixed-width grammar: 6 + 10 + 8 = 24-char records.
    private static final String FW_GRAMMAR = """
              csv_settings:
                frontend: fixedwidth
                has_header: false
                date_formats[1]: "%Y-%m-%d"
                timestamp_formats[1]: "%Y-%m-%d"
                fixedwidth:
                  record: line
                  trim: both
                  fields[3]{name,start,length}:
                    ACCOUNT_NUMBER,0,6
                    EVENT_DATE,6,10
                    AMOUNT,16,8
            """;

    // ┌ACCOUNT┐┌─EVENT_DATE─┐┌─AMOUNT─┐   (6 + 10 + 8 = 24)
    //  A00001  2020-04-03      1234.5
    private static final String DATA = """
            A000012020-04-03  1234.5
            B000022020-04-04  9999.0
            footer
            """;

    @Test
    void carvesFixedWidthColumnsNatively(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "fw", FW_GRAMMAR);
        assertNotNull(cfg.fixedWidth(), "fixed-width frontend parsed");
        assertFalse(cfg.fixedWidth().binary());
        assertEquals(24, cfg.fixedWidth().minRecordLength(), "min length defaults to the widest slice end");
        assertTrue(DuckDbCsvIngester.usesDuckDb(cfg), "fixed-width text is always native");

        File dat = write(dir, "sub.dat", DATA);
        try (Connection conn = open()) {
            DuckDbCsvIngester.ingest(dat, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"),
                    "footer line (len 6 < 24) dropped; two records carved");
            assertEquals(List.of("2020-04-03", "2020-04-04"), col(conn, "raw_f0", "EVENT_DATE"));
            assertEquals(List.of("1234.5", "9999.0"), col(conn, "raw_f0", "AMOUNT"),
                    "right-padded AMOUNT trimmed");
        }
    }

    @Test
    void trimNonePreservesPadding(@TempDir Path dir) throws Exception {
        String grammar = FW_GRAMMAR.replace("trim: both", "trim: none");
        PipelineConfig cfg = load(dir, "fwnt", grammar);
        File dat = write(dir, "sub.dat", DATA);
        try (Connection conn = open()) {
            DuckDbCsvIngester.ingest(dat, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("  1234.5", "  9999.0"), col(conn, "raw_f0", "AMOUNT"),
                    "trim: none keeps the leading pad");
        }
    }

    @Test
    void lineWithDelimiterCharsStaysIntact(@TempDir Path dir) throws Exception {
        // A record whose data contains commas and quotes must not be split or quote-merged.
        PipelineConfig cfg = load(dir, "fwq", FW_GRAMMAR);
        File dat = write(dir, "q.dat", "A,0001\"2020-04-03\"x,y,z!!\n");  // 24 chars with , and "
        try (Connection conn = open()) {
            DuckDbCsvIngester.ingest(dat, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("A,0001"), col(conn, "raw_f0", "ACCOUNT_NUMBER"),
                    "commas/quotes inside the record do not split or merge the line");
        }
    }

    /** The shipped worked-example pipeline references the fixed-width grammar by relative path. */
    @Test
    void shippedSubscriberPipelineResolvesFixedWidthGrammar() throws Exception {
        Path pipeline = Path.of("config/subscriber/subscriber_pipeline.toon");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(pipeline),
                "shipped subscriber pipeline present (module CWD)");
        // fromMap is a pure parse (no dir creation) but still resolves the grammar + schema files.
        PipelineConfig cfg = PipelineConfig.fromMap(
                com.gamma.util.ToonHelper.load(pipeline.toString()));
        assertNotNull(cfg.fixedWidth(), "frontend: fixedwidth resolved from the grammar file");
        assertFalse(cfg.fixedWidth().binary());
        assertEquals(4, cfg.fixedWidth().slices().size());
        assertEquals(40, cfg.fixedWidth().minRecordLength(), "default min = widest slice end (28+12)");
        assertEquals(PipelineConfig.FixedWidth.Trim.BOTH, cfg.fixedWidth().trim());
    }

    // ── validation ──────────────────────────────────────────────────────────────

    @Test
    void selectorBeyondSlicesFailsLoad(@TempDir Path dir) {
        // Drop the 3rd slice (operate on the runtime string) so the schema's selector 2 has no slice.
        String twoSlice = FW_GRAMMAR.replace("\n        AMOUNT,16,8", "").replace("fields[3]", "fields[2]");
        Exception e = assertThrows(Exception.class, () -> load(dir, "bad", twoSlice));
        assertTrue(String.valueOf(e.getMessage()).contains("no matching fixedwidth slice"),
                "selector-out-of-range fails the load: " + e.getMessage());
    }

    @Test
    void binaryWithoutRecordLengthFailsLoad(@TempDir Path dir) {
        String bin = FW_GRAMMAR.replace("record: line", "record: bytes");
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "bin", bin));
        assertTrue(e.getMessage().contains("record_length must be > 0"), e.getMessage());
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
        return DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("fw_"));
    }

    private static File write(Path dir, String name, String content) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), content);
        return f;
    }

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    /** Build + load a pipeline whose processing block embeds {@code procExtra} (the csv_settings/grammar). */
    private static PipelineConfig load(Path dir, String tag, String procExtra) throws Exception {
        Path schema = dir.resolve("schema_" + tag + ".toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        String d = fwd(dir);
        String pipe =
                "name: FW_" + tag + "\n" +
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
                "  file_pattern: \"glob:**/*.dat\"\n" +
                "  schema_file: " + fwd(schema) + "\n" +
                procExtra;
        Path p = dir.resolve("pipe_" + tag + ".toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
