package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DuckDbCsvIngester} — the native vectorized CSV path.
 *
 * <p>The headline test is {@link #parityWithJavaEngineOnCleanFile}: the same input
 * through both the Java and DuckDB ingesters must produce byte-identical table
 * contents. That parity is the contract that lets {@code engine: auto} switch
 * engines safely on clean configs.
 */
class DuckDbCsvIngesterTest {

    /** 3-column schema: ID, AMT(declared DOUBLE but stored VARCHAR at ingest), TXN_DATE. */
    private static Map<String, Object> schema() {
        return Map.of("raw", Map.of("fields", List.of(
                Map.of("name", "ID",       "selector", "0", "type", "VARCHAR"),
                Map.of("name", "AMT",      "selector", "1", "type", "DOUBLE"),
                Map.of("name", "TXN_DATE", "selector", "2", "type", "DATE"))));
    }

    private static PipelineConfig cfg(Path dir, String engine) throws Exception {
        Path schemaFile = dir.resolve("s.toon");
        Files.writeString(schemaFile, """
                partitionKey: TXN_DATE
                raw:
                  name: t
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID,"0",VARCHAR
                mapping:
                  canonicalName: t
                  rawName: t
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                """);
        Path p = dir.resolve("p.toon");
        Files.writeString(p, """
                name: T_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: %s
                  csv_settings:
                    delimiter: ","
                    engine: %s
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir,
                schemaFile.toString().replace("\\", "/"), engine));
        return PipelineConfig.load(p.toString());
    }

    /** Both engines must yield identical rows for a clean, well-formed file. */
    @Test
    void parityWithJavaEngineOnCleanFile(@TempDir Path dir) throws Exception {
        File csv = dir.resolve("clean.csv").toFile();
        Files.writeString(csv.toPath(), """
                ID,AMT,TXN_DATE
                1,10.5,2020-04-01
                2,20.0,2020-04-02
                3,30.25,2020-04-03
                """);

        List<String[]> javaRows = ingestAndDump(dir, "java", csv);
        List<String[]> duckRows = ingestAndDump(dir, "duckdb", csv);

        assertEquals(3, javaRows.size(), "java engine row count");
        assertEquals(javaRows.size(), duckRows.size(), "row count parity");
        for (int i = 0; i < javaRows.size(); i++)
            assertArrayEquals(javaRows.get(i), duckRows.get(i),
                    "row " + i + " differs between engines");
    }

    /** Short rows, footers and blank lines are rejected — matching the Java path. */
    @Test
    void rejectsShortRowsAndFooterLikeJava(@TempDir Path dir) throws Exception {
        File csv = dir.resolve("messy.csv").toFile();
        Files.writeString(csv.toPath(), String.join("\n",
                "ID,AMT,TXN_DATE",
                "1,10.5,2020-04-01",
                "2,20.0,2020-04-02",
                "3,30.0",                 // short — rejected
                "",                       // blank — rejected
                "2 rows selected.") + "\n");

        File db = DuckDbUtil.tempDbFile("dt_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            IngestResult r = DuckDbCsvIngester.ingest(csv, conn, schema(), cfg(dir, "duckdb"), "raw_f0");
            assertEquals(2, r.parsedRows(), "only the 2 well-formed rows survive");
            assertTrue(r.errorRows() >= 2, "short row + footer should be rejected, got " + r.errorRows());

            // Error CSV written with rejected lines
            Path errCsv = dir.resolve("errors").resolve("messy_errors.csv");
            assertTrue(Files.exists(errCsv), "error CSV should be written for rejected rows");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** usesDuckDb routing: auto on clean config = native; messy knobs = java. */
    @Test
    void engineRoutingPolicy(@TempDir Path dir) throws Exception {
        assertTrue(DuckDbCsvIngester.usesDuckDb(cfg(dir, "auto")),
                "auto + clean config should route to DuckDB");
        assertTrue(DuckDbCsvIngester.usesDuckDb(cfg(dir, "duckdb")),
                "explicit duckdb always routes native");
        assertFalse(DuckDbCsvIngester.usesDuckDb(cfg(dir, "java")),
                "explicit java never routes native");
    }

    /**
     * 4.1 routing: {@code auto} now stays native for {@code skip_tail_columns} (the boundary scan
     * declares a wide-enough column set), but still falls back to Java for {@code skip_tail_lines}
     * (footer-line dropping has no native equivalent).
     */
    @Test
    void autoEngineRoutingForMessyKnobs(@TempDir Path dir) throws Exception {
        assertTrue(DuckDbCsvIngester.usesDuckDb(messyCfg(dir, "tc", "skip_tail_columns: 2")),
                "auto + skip_tail_columns should be native-eligible in 4.1");
        assertFalse(DuckDbCsvIngester.usesDuckDb(messyCfg(dir, "tl", "skip_tail_lines: 2")),
                "auto must still fall back to Java for skip_tail_lines (footer drop)");
    }

    /** Build a pipeline whose csv_settings carries one extra messy-knob line. */
    private static PipelineConfig messyCfg(Path dir, String tag, String knobLine) throws Exception {
        Path schemaFile = dir.resolve("s_" + tag + ".toon");
        Files.writeString(schemaFile, """
                partitionKey: TXN_DATE
                raw:
                  name: t
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID,"0",VARCHAR
                mapping:
                  canonicalName: t
                  rawName: t
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                """);
        Path p = dir.resolve("p_" + tag + ".toon");
        Files.writeString(p, """
                name: T2_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: %s
                  csv_settings:
                    delimiter: ","
                    engine: auto
                    %s
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir,
                schemaFile.toString().replace("\\", "/"), knobLine));
        return PipelineConfig.load(p.toString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<String[]> ingestAndDump(Path dir, String engine, File csv) throws Exception {
        File db = DuckDbUtil.tempDbFile("dt_" + engine + "_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            PipelineConfig c = cfg(dir, engine);
            if ("java".equals(engine))
                CsvIngester.ingest(csv, conn, schema(), c, "raw_f0");
            else
                DuckDbCsvIngester.ingest(csv, conn, schema(), c, "raw_f0");

            List<String[]> rows = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT ID, AMT, TXN_DATE FROM raw_f0 ORDER BY ID")) {
                while (rs.next())
                    rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            }
            return rows;
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
