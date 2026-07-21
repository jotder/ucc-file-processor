package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link DataTransformer}'s less-exercised mapping transform types
 * ({@code CONCAT_DT}, {@code FILENAME_DATE}) and the {@code DOUBLE}/{@code INTEGER}
 * partition-column casts.
 */
class DataTransformerTransformTypesTest {

    /** Build a PipelineConfig whose only relevant content is the date/ts formats. */
    private static PipelineConfig cfg(Path dir, String tsFormats) throws Exception {
        return TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema())
                .dateFormats("\"%Y-%m-%d\"")
                .tsFormats(tsFormats)
                .load();
    }

    /** CONCAT_DT joins a date column and a time column into a TIMESTAMP. */
    @Test
    void concatDtBuildsTimestamp(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, "\"%Y-%m-%d %H:%M:%S\"");
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "TRADE_DATE", "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "TRADE_TIME", "selector", "1", "type", "VARCHAR"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "TRADE_TS",
                               "sourceExpression", "TRADE_DATE|TRADE_TIME",
                               "transformType", "CONCAT_DT"))));

        File db = DuckDbUtil.tempDbFile("dt_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES " +
                    "('2020-04-03','13:45:00',0)) t(TRADE_DATE,TRADE_TIME,__src_id)");
            DataTransformer.materialize(conn, schema, cfg, "src", "dst");
            try (ResultSet rs = st.executeQuery("SELECT TRADE_TS FROM dst")) {
                assertTrue(rs.next());
                // DuckDB renders TIMESTAMP with a fractional-second suffix (…:00.0).
                assertTrue(rs.getString(1).startsWith("2020-04-03 13:45:00"),
                        "got: " + rs.getString(1));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * A DIRECT-mapped DATE column with NO {@code date_formats} declared must still cast via
     * DuckDB's native ISO parse — not emit a zero-arg {@code COALESCE()::DATE} (invalid SQL that
     * failed the whole transform, surfacing as a QUARANTINED_UNREADABLE batch; found by the stream
     * onboarding P2 live walk, where the guided Schema stage types a column DATE but never captures
     * a format). See {@link com.gamma.util.SqlBuilder#appendCoalesce}.
     */
    @Test
    void directDateColumnWithoutFormatsCastsIsoNatively(@TempDir Path dir) throws Exception {
        // A minimal draft-shaped config with NO date_formats — exactly what stream onboarding writes.
        PipelineConfig cfg = PipelineConfig.fromMap(Map.of(
                "name", "no_fmt",
                "dirs", Map.of("poll", "in", "database", "out"),
                "processing", Map.of("threads", 1)));
        assertTrue(cfg.csv().dateFormats().isEmpty(), "no date_formats declared");
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ORDER_DATE", "selector", "0", "type", "DATE"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "ORDER_DATE", "sourceExpression", "ORDER_DATE"))));

        File db = DuckDbUtil.tempDbFile("dt_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES " +
                    "('2026-07-16',0), ('not-a-date',0)) t(ORDER_DATE,__src_id)");
            DataTransformer.materialize(conn, schema, cfg, "src", "dst");   // must not throw
            try (ResultSet rs = st.executeQuery("SELECT ORDER_DATE FROM dst ORDER BY ORDER_DATE NULLS LAST")) {
                assertTrue(rs.next());
                assertEquals("2026-07-16", rs.getString(1), "ISO date parsed natively");
                assertTrue(rs.next());
                assertNull(rs.getString(1), "unparseable value → NULL (TRY_CAST), not a crash");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** EXPR emits the sourceExpression verbatim — runs DuckDB scalar functions end-to-end. */
    @Test
    void exprRunsArbitraryDuckDbExpression(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, "\"%Y-%m-%d %H:%M:%S\"");
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "NAME",   "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "AMT",    "selector", "1", "type", "VARCHAR"),
                        Map.of("name", "STATUS", "selector", "2", "type", "VARCHAR"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "NAME_UC",
                               "sourceExpression", "UPPER(TRIM(NAME))", "transformType", "EXPR"),
                        Map.of("targetColumn", "AMOUNT_MAJOR",
                               "sourceExpression", "TRY_CAST(AMT AS DOUBLE) / 100.0", "transformType", "EXPR"),
                        Map.of("targetColumn", "RESULT",
                               "sourceExpression", "CASE WHEN STATUS = '0' THEN 'OK' ELSE 'FAIL' END",
                               "transformType", "EXPR"))));

        File db = DuckDbUtil.tempDbFile("dt_expr_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES " +
                    "('  jane  ','12345','0',0)) t(NAME,AMT,STATUS,__src_id)");
            DataTransformer.materialize(conn, schema, cfg, "src", "dst");
            try (ResultSet rs = st.executeQuery("SELECT NAME_UC, AMOUNT_MAJOR, RESULT FROM dst")) {
                assertTrue(rs.next());
                assertEquals("JANE", rs.getString("NAME_UC"));
                assertEquals(123.45, rs.getDouble("AMOUNT_MAJOR"), 1e-9);
                assertEquals("OK", rs.getString("RESULT"));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** FILENAME_DATE extracts an 8-digit date embedded in a column value. */
    @Test
    void filenameDateExtractsDate(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, "\"%Y-%m-%d\"");
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "FILE_NAME", "selector", "0", "type", "VARCHAR"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "EVENT_DATE",
                               "sourceExpression", "FILE_NAME|data_|%Y%m%d",
                               "transformType", "FILENAME_DATE"))));

        File db = DuckDbUtil.tempDbFile("dt2_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES " +
                    "('data_20200403.csv.gz',0)) t(FILE_NAME,__src_id)");
            DataTransformer.materialize(conn, schema, cfg, "src", "dst");
            try (ResultSet rs = st.executeQuery("SELECT EVENT_DATE FROM dst")) {
                assertTrue(rs.next());
                assertEquals("2020-04-03", rs.getString(1));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** FILENAME_DATE is only valid for the EVENT_DATE target column. */
    @Test
    void filenameDateRejectsNonEventDateTarget(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, "\"%Y-%m-%d\"");
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "FILE_NAME", "selector", "0", "type", "VARCHAR"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "SOME_OTHER_COL",
                               "sourceExpression", "FILE_NAME|data_|%Y%m%d",
                               "transformType", "FILENAME_DATE"))));

        File db = DuckDbUtil.tempDbFile("dt3_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES ('x',0)) t(FILE_NAME,__src_id)");
            assertThrows(IllegalArgumentException.class,
                    () -> DataTransformer.materialize(conn, schema, cfg, "src", "dst"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** DOUBLE and INTEGER partition columns use TRY_CAST. */
    @Test
    void doubleAndIntegerPartitionCasts(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, "\"%Y-%m-%d\"");
        Map<String, Object> schema = Map.of(
                "partitions", List.of(
                        Map.of("column", "amt",  "source", "AMT", "type", "DOUBLE"),
                        Map.of("column", "qty",  "source", "QTY", "type", "INTEGER")),
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID",  "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "AMT", "selector", "1", "type", "VARCHAR"),
                        Map.of("name", "QTY", "selector", "2", "type", "VARCHAR"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "ID", "sourceExpression", "ID", "transformType", "DIRECT"))));

        File db = DuckDbUtil.tempDbFile("dt4_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES " +
                    "('r1','3.14','42',0)) t(ID,AMT,QTY,__src_id)");
            DataTransformer.materialize(conn, schema, cfg, "src", "dst");
            try (ResultSet rs = st.executeQuery("SELECT amt, qty FROM dst")) {
                assertTrue(rs.next());
                assertEquals(3.14, rs.getDouble("amt"), 1e-9);
                assertEquals(42, rs.getInt("qty"));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
