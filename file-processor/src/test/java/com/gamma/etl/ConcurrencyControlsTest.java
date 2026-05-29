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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the controllable-concurrency knobs added in v1.5.0:
 * {@code DuckDbUtil.applyWorkerThreads} (per-connection {@code PRAGMA threads}) and
 * the {@link ConfigValidator} CPU-oversubscription warning.
 */
class ConcurrencyControlsTest {

    /** PRAGMA threads cap is actually applied to the connection. */
    @Test
    void applyWorkerThreadsSetsPragma() throws Exception {
        File db = DuckDbUtil.tempDbFile("ct_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            DuckDbUtil.applyWorkerThreads(conn, 3);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT current_setting('threads')")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "PRAGMA threads should cap DuckDB to 3");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** threads <= 0 is a no-op: DuckDB keeps its default (>= 1, machine-dependent). */
    @Test
    void applyWorkerThreadsZeroLeavesDefault() throws Exception {
        File db = DuckDbUtil.tempDbFile("ct0_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            DuckDbUtil.applyWorkerThreads(conn, 0);   // no-op
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT current_setting('threads')")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "default thread count should be >= 1");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** threads × duckdb_threads far above any core count must trigger the warning. */
    @Test
    void validatorWarnsOnOversubscription(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, /*threads*/ 64, /*duckdbThreads*/ 64);
        List<String> warnings = ConfigValidator.validate(cfg);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("oversubscribe")),
                "expected an oversubscription warning, got: " + warnings);
    }

    /** A modest, within-cores config must NOT warn about oversubscription. */
    @Test
    void validatorSilentWhenWithinCores(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, /*threads*/ 1, /*duckdbThreads*/ 1);
        List<String> warnings = ConfigValidator.validate(cfg);
        assertFalse(warnings.stream().anyMatch(w -> w.contains("oversubscribe")),
                "1×1 must not warn, got: " + warnings);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static PipelineConfig load(Path dir, int threads, int duckdbThreads) throws Exception {
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
                name: C_ETL
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
                  threads: %d
                  duckdb_threads: %d
                  file_pattern: "glob:**/*.csv"
                  schema_file: %s
                  csv_settings:
                    delimiter: ","
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir,
                threads, duckdbThreads, schemaFile.toString().replace("\\", "/")));
        return PipelineConfig.load(p.toString());
    }
}
