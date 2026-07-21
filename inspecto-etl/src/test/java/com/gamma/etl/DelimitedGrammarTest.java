package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileNotFoundException;
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
 * Tests for the 4.1 delimited-grammar work (docs/delimited-grammar-design.md):
 * <ul>
 *   <li><b>Phase A</b> — external {@code processing.grammar} file, with inline {@code csv_settings}
 *       overlay/override and missing-file failure.</li>
 *   <li><b>Phase B</b> — native {@code read_csv} pass-throughs ({@code null_strings} → {@code nullstr}).</li>
 *   <li><b>Phase C</b> — row filters ({@code include/exclude_prefixes} + {@code include/exclude_regex})
 *       on both the native and Java paths, with parity.</li>
 *   <li><b>Phase D</b> — boundary pre-scan: a SQL*Plus-style preamble parses natively (parity with
 *       Java), {@code skip_tail_columns} parses natively, and {@code decideNative} falls back to Java
 *       when boundaries don't resolve.</li>
 * </ul>
 */
class DelimitedGrammarTest {

    // 3-column schema: MARKER(c0), ID(c1), VAL(c2). MARKER is the row-filter target column.
    private static final String SCHEMA = """
            partitionKey: VAL
            raw:
              name: t
              format: CSV
              fields[3]{name,selector,type}:
                MARKER,"0",VARCHAR
                ID,"1",VARCHAR
                VAL,"2",VARCHAR
            mapping:
              canonicalName: t
              rawName: t
              rules[3]{targetColumn,sourceExpression,transformType}:
                MARKER,MARKER,DIRECT
                ID,ID,DIRECT
                VAL,VAL,DIRECT
            """;

    // ── Phase A: external grammar file ─────────────────────────────────────────

    @Test
    void grammarFileSuppliesCsvSettings(@TempDir Path dir) throws Exception {
        Path grammar = dir.resolve("t.grammar.toon");
        Files.writeString(grammar, """
                delimiter: ";"
                has_header: false
                skip_junk_lines: 5
                encoding: latin-1
                exclude_prefixes[2]: "Start", "Stop"
                filter_target_column: 0
                """);
        PipelineConfig cfg = load(dir, "g", "  grammar: " + fwd(grammar) + "\n");

        assertEquals(";", cfg.csv().delimiter());
        assertFalse(cfg.csv().hasHeader());
        assertEquals(5, cfg.csv().skipJunkLines());
        assertEquals("latin-1", cfg.csv().encoding());
        assertEquals(List.of("Start", "Stop"), cfg.csv().excludePrefixes());
        assertEquals(0, cfg.csv().filterTargetColumn());
    }

    @Test
    void inlineCsvSettingsOverridesGrammar(@TempDir Path dir) throws Exception {
        Path grammar = dir.resolve("base.grammar.toon");
        Files.writeString(grammar, """
                delimiter: ";"
                skip_junk_lines: 5
                """);
        // grammar sets delim=";" skipJunk=5; inline overrides delim="|" only.
        PipelineConfig cfg = load(dir, "ov",
                "  grammar: " + fwd(grammar) + "\n"
              + "  csv_settings:\n"
              + "    delimiter: \"|\"\n");

        assertEquals("|", cfg.csv().delimiter(), "inline wins");
        assertEquals(5, cfg.csv().skipJunkLines(), "grammar value preserved when not overridden");
    }

    /** The shipped voucher pipeline references an external grammar file by relative path. */
    @Test
    void shippedVoucherPipelineResolvesGrammar() throws Exception {
        Path pipeline = Path.of("config/voucher/voucher_pipeline.toon");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(pipeline),
                "shipped voucher pipeline present (module CWD)");
        // fromMap is a pure parse (no status-dir creation) but still resolves processing.grammar.
        PipelineConfig cfg = PipelineConfig.fromMap(
                com.gamma.util.ToonHelper.load(pipeline.toString()));
        assertEquals(",", cfg.csv().delimiter(), "delimiter comes from the grammar file");
        assertFalse(cfg.csv().hasHeader(), "has_header comes from the grammar file");
        assertTrue(cfg.csv().dateFormats().contains("%d-%b-%Y %H:%M:%S"),
                "Oracle DD-MON-YYYY format resolved from grammar, got " + cfg.csv().dateFormats());
    }

    @Test
    void missingGrammarFileFails(@TempDir Path dir) {
        Exception e = assertThrows(FileNotFoundException.class,
                () -> load(dir, "missing", "  grammar: " + fwd(dir.resolve("nope.toon")) + "\n"));
        assertTrue(e.getMessage().contains("Grammar file not found"));
    }

    // ── Phase B: native pass-throughs ──────────────────────────────────────────

    @Test
    void nullStringsBecomeSqlNull(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "ns",
                "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: duckdb\n"
              + "    has_header: true\n"
              + "    null_strings[2]: \"NULL\", \"NA\"\n");
        File csv = write(dir, "ns.csv", """
                MARKER,ID,VAL
                A,1,x
                B,NULL,y
                C,NA,z
                """);
        try (Connection conn = open()) {
            DuckDbCsvIngester.ingest(csv, conn, cfg.schemas().single(), cfg, "raw_f0");
            List<String> ids = column(conn, "raw_f0", "ID");
            assertEquals(3, ids.size());
            assertTrue(ids.contains(null), "NULL/NA sentinels should become SQL NULL, got " + ids);
            assertEquals(2, ids.stream().filter(v -> v == null).count(), "two sentinels → two NULLs");
        }
    }

    // ── Phase C: row filters (native + parity) ─────────────────────────────────

    private static final String FILTER_CSV = """
            MARKER,ID,VAL
            DATA,1,x
            Start,2,y
            DATA,3,z
            Stop,4,w
            """;

    @Test
    void excludePrefixesNative(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "exn",
                "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: duckdb\n"
              + "    has_header: true\n"
              + "    exclude_prefixes[2]: \"Start\", \"Stop\"\n"
              + "    filter_target_column: 0\n");
        assertEquals(List.of("DATA", "DATA"), markers(dir, cfg, "exn.csv", FILTER_CSV, true));
    }

    @Test
    void includePrefixesNative(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "inn",
                "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: duckdb\n"
              + "    has_header: true\n"
              + "    include_prefixes[1]: \"DATA\"\n"
              + "    filter_target_column: 0\n");
        assertEquals(List.of("DATA", "DATA"), markers(dir, cfg, "inn.csv", FILTER_CSV, true));
    }

    @Test
    void excludeRegexNative(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "rxn",
                "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: duckdb\n"
              + "    has_header: true\n"
              + "    exclude_regex[1]: \"^St\"\n"
              + "    filter_target_column: 0\n");
        assertEquals(List.of("DATA", "DATA"), markers(dir, cfg, "rxn.csv", FILTER_CSV, true));
    }

    @Test
    void filtersParityNativeVsJava(@TempDir Path dir) throws Exception {
        String body = "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: %s\n"
              + "    has_header: true\n"
              + "    exclude_prefixes[2]: \"Start\", \"Stop\"\n"
              + "    filter_target_column: 0\n";
        PipelineConfig duck = load(dir, "pd", String.format(body, "duckdb"));
        PipelineConfig java = load(dir, "pj", String.format(body, "java"));
        List<String> native_ = markers(dir, duck, "pd.csv", FILTER_CSV, true);
        List<String> javaRows = markers(dir, java, "pj.csv", FILTER_CSV, false);
        assertEquals(List.of("DATA", "DATA"), javaRows, "java path filters too");
        assertEquals(native_, javaRows, "native and java filtering must agree");
    }

    // ── Phase D: boundary pre-scan ─────────────────────────────────────────────

    private static final String SQLPLUS = """
            SQL*Plus: Release 19.0.0.0.0 Production
            Enter password:
            ERROR at line 1: ORA-01017: invalid username/password
            Connected to Oracle Database 19c

            1000,A,x
            1001,B,y
            1002,C,z
            """;

    @Test
    void sqlPlusPreambleParsesNativelyWithParity(@TempDir Path dir) throws Exception {
        String body = "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: %s\n"
              + "    has_header: false\n"
              + "    skip_junk_lines: -1\n";
        PipelineConfig duck = load(dir, "spd", String.format(body, "duckdb"));
        PipelineConfig java = load(dir, "spj", String.format(body, "java"));

        List<String> nativeM = markers(dir, duck, "spd.csv", SQLPLUS, true);
        List<String> javaM   = markers(dir, java, "spj.csv", SQLPLUS, false);
        assertEquals(List.of("1000", "1001", "1002"), nativeM, "native skips the preamble via pre-scan");
        assertEquals(nativeM, javaM, "native and Java must agree on the preamble skip");
    }

    @Test
    void skipTailColumnsParsesNatively(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "stc",
                "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: duckdb\n"
              + "    has_header: false\n"
              + "    skip_tail_columns: 2\n");
        // 5 physical columns; schema wants 3 (max selector 2) + 2 trailing extras.
        File csv = write(dir, "stc.csv", """
                1000,A,x,extra1,extra2
                1001,B,y,extra1,extra2
                """);
        try (Connection conn = open()) {
            DuckDbCsvIngester.ingest(csv, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("1000", "1001"), column(conn, "raw_f0", "MARKER"),
                    "wide rows accepted natively when skip_tail_columns declares the extra width");
            assertEquals(List.of("x", "y"), column(conn, "raw_f0", "VAL"),
                    "selectors still bind correctly past the extra columns");
        }
    }

    @Test
    void decideNativeRoutesByResolvability(@TempDir Path dir) throws Exception {
        PipelineConfig auto = load(dir, "dn",
                "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: auto\n"
              + "    has_header: false\n"
              + "    skip_junk_lines: -1\n");

        File ok = write(dir, "ok.csv", SQLPLUS);
        assertTrue(DuckDbCsvIngester.decideNative(batch(auto, ok), auto),
                "resolvable SQL*Plus preamble → native");

        // A file with no data row within the window (all 1-column junk) → unresolved → Java.
        File junk = write(dir, "junk.csv", "banner only\nstill banner\nmore banner\n");
        assertFalse(DuckDbCsvIngester.decideNative(batch(auto, junk), auto),
                "no resolvable data row → fall back to Java");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Ingest {@code content} through the native or Java path and return the MARKER column. */
    private static List<String> markers(Path dir, PipelineConfig cfg, String csvName,
                                        String content, boolean nativePath) throws Exception {
        File csv = write(dir, csvName, content);
        try (Connection conn = open()) {
            if (nativePath) DuckDbCsvIngester.ingest(csv, conn, cfg.schemas().single(), cfg, "raw_f0");
            else            CsvIngester.ingest(csv, conn, cfg.schemas().single(), cfg, "raw_f0");
            return column(conn, "raw_f0", "MARKER");
        }
    }

    private static List<String> column(Connection conn, String table, String col) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + col + "\" FROM \"" + table + "\" ORDER BY \"ID\"")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Batch batch(PipelineConfig cfg, File f) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        Batch.Member m = new Batch.Member(f, 0, f.length(), sel);
        return new Batch("b1", "t", null, List.of(m));
    }

    private static Connection open() throws Exception {
        return DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("dg_"));
    }

    private static File write(Path dir, String name, String content) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), content);
        return f;
    }

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    /** Build + load a pipeline whose processing block contains {@code procExtra} (grammar/csv_settings). */
    private static PipelineConfig load(Path dir, String tag, String procExtra) throws Exception {
        Path schema = dir.resolve("schema_" + tag + ".toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        String d = fwd(dir);
        String pipe =
                "name: G_" + tag + "\n" +
                "version: 1\n" +
                "dirs:\n" +
                "  poll: " + d + "/inbox\n" +
                "  database: " + d + "/db\n" +
                "  backup: " + d + "/backup\n" +
                "  temp: " + d + "/temp\n" +
                "  errors: " + d + "/errors\n" +
                "  quarantine: " + d + "/quarantine\n" +
                "  status_dir: " + d + "/status\n" +
                "  log_dir: " + d + "/logs\n" +
                "output:\n" +
                "  format: CSV\n" +
                "processing:\n" +
                "  threads: 1\n" +
                "  file_pattern: \"glob:**/*.csv\"\n" +
                "  schema_file: " + fwd(schema) + "\n" +
                procExtra;
        Path p = dir.resolve("pipe_" + tag + ".toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
