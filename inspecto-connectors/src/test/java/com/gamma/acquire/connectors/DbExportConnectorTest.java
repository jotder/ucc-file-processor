package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SourceConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-export source — exercises {@link DbExportConnector} against an in-process DuckDB JDBC database (the driver is
 * already on the test classpath transitively, so no embedded Postgres is needed; the connector is JDBC-generic).
 */
class DbExportConnectorTest {

    /** Create a DuckDB file with a small table and return its {@code jdbc:duckdb:} URL. */
    private static String seedDuckDb(Path dir, String tableDdlAndInserts) throws Exception {
        Path db = dir.resolve("export_src.duckdb");
        String url = "jdbc:duckdb:" + db.toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            for (String sql : tableDdlAndInserts.split(";")) {
                if (!sql.isBlank()) st.execute(sql);
            }
        }   // close so the connector opens its own reader connection
        return url;
    }

    private static ConnectionProfile profile(String id, String url, String query, String exportName) {
        return new ConnectionProfile(id, "db", null, 0, null, null, null, null,
                Map.of("jdbc_url", url, "query", query, "export_name", exportName), null);
    }

    @Test
    void exportsQueryResultAsCsv(@TempDir Path dir) throws Exception {
        String url = seedDuckDb(dir, """
            CREATE TABLE cdr(ID INTEGER, AMT DOUBLE, EVENT_DATE DATE);
            INSERT INTO cdr VALUES (1, 1.5, DATE '2020-04-03'), (2, 2.5, DATE '2020-04-04');
            """);
        ConnectionProfile p = profile("t", url, "SELECT * FROM cdr ORDER BY ID", "cdr.csv");

        try (SourceConnector c = new DbExportConnector(p)) {
            List<RemoteFile> found = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED));
            assertEquals(1, found.size());
            assertEquals("cdr.csv", found.get(0).relativePath());

            Path dest = dir.resolve("out/cdr.csv");
            c.fetchTo(found.get(0), dest);
            List<String> lines = Files.readAllLines(dest, StandardCharsets.UTF_8);
            assertEquals("\"ID\",\"AMT\",\"EVENT_DATE\"", lines.get(0), "fully-quoted header from the result set");
            assertEquals("\"1\",\"1.5\",\"2020-04-03\"", lines.get(1));
            assertEquals("\"2\",\"2.5\",\"2020-04-04\"", lines.get(2));
            assertEquals(3, lines.size());
        }
    }

    @Test
    void streamsViaOpen(@TempDir Path dir) throws Exception {
        String url = seedDuckDb(dir, "CREATE TABLE t(a INTEGER); INSERT INTO t VALUES (7);");
        ConnectionProfile p = profile("s", url, "SELECT * FROM t", "t.csv");
        try (SourceConnector c = new DbExportConnector(p)) {
            RemoteFile rf = c.discover(new DiscoveryContext(List.of(), List.of(), DiscoveryContext.UNBOUNDED)).get(0);
            try (var in = c.open(rf)) {
                String csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertEquals("\"a\"\n\"7\"\n", csv);
            }
        }
    }

    @Test
    void resolvesDateTokensInQueryAndName() {
        ZonedDateTime when = ZonedDateTime.of(2026, 6, 15, 9, 5, 0, 0, ZoneOffset.UTC);
        assertEquals("cdr_20260615.csv", DbExportConnector.resolveTokens("cdr_{yyyyMMdd}.csv", when));
        assertEquals("SELECT * FROM cdr WHERE d = '2026-06-15'",
                DbExportConnector.resolveTokens("SELECT * FROM cdr WHERE d = '{yyyy-MM-dd}'", when));
        assertEquals("no tokens here", DbExportConnector.resolveTokens("no tokens here", when));
    }

    @Test
    void missingQueryOrNameFailsFast(@TempDir Path dir) {
        ConnectionProfile noQuery = new ConnectionProfile("x", "db", null, 0, null, null, null, null,
                Map.of("jdbc_url", "jdbc:duckdb:", "export_name", "x.csv"), null);
        assertThrows(IllegalArgumentException.class, () -> new DbExportConnector(noQuery));
    }

    @Test
    void endToEndDbExportIsIngested(@TempDir Path dir) throws Exception {
        String url = seedDuckDb(dir, """
            CREATE TABLE cdr(ID VARCHAR, AMT DOUBLE, EVENT_DATE DATE);
            INSERT INTO cdr VALUES ('r1', 1.0, DATE '2020-04-03'), ('r2', 2.0, DATE '2020-04-04');
            """);
        ConnectionProfile p = profile("test-db", url, "SELECT * FROM cdr ORDER BY ID", "cdr_export.csv");
        ConnectionRegistry.register(p);
        try {
            com.gamma.etl.PipelineConfig cfg = com.gamma.etl.PipelineConfig.load(writeDbPipeline(dir).toString());
            com.gamma.inspector.SourceProcessor.run(cfg);
            try (var w = Files.walk(Path.of(cfg.dirs().database()))) {
                assertTrue(w.anyMatch(f -> f.getFileName().toString().endsWith("_out.csv")),
                        "the DB export was ingested to an output file");
            }
        } finally {
            ConnectionRegistry.remove("test-db");
        }
    }

    private Path writeDbPipeline(Path dir) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """);
        String toon = """
            name: DB_ETL
            version: 1
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              errors: %s/errors
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
              log_dir: %s/logs
            source:
              connector: db
              connection: test-db
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
              batch:
                max_files: 100
                max_bytes: 268435456
              csv_settings:
                delimiter: ","
                skip_header_lines: 1
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                          schema.toString().replace("\\", "/"));
        Path pth = dir.resolve("db_pipeline.toon");
        Files.writeString(pth, toon);
        return pth;
    }
}
