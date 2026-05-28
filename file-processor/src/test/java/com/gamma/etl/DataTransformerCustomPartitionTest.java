package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataTransformerCustomPartitionTest {

    /**
     * Schema with explicit partitions[]: event_type (VARCHAR) + date components.
     * Simulates the custom ingester path where event_type is a pre-computed column
     * the ingester added to the raw table.
     */
    @Test
    void materialisesWithExplicitPartitions(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> schema = Map.of(
                "partitions", List.of(
                        Map.of("column", "event_type", "source", "EVENT_TYPE", "type", "VARCHAR"),
                        Map.of("column", "year",  "source", "EVENT_DATE", "type", "DATE_YEAR"),
                        Map.of("column", "month", "source", "EVENT_DATE", "type", "DATE_MONTH"),
                        Map.of("column", "day",   "source", "EVENT_DATE", "type", "DATE_DAY")),
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID",         "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "EVENT_TYPE", "selector", "1", "type", "VARCHAR"),
                        Map.of("name", "EVENT_DATE", "selector", "2", "type", "DATE"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "ID",         "sourceExpression", "ID",         "transformType", "DIRECT"),
                        Map.of("targetColumn", "EVENT_TYPE", "sourceExpression", "EVENT_TYPE", "transformType", "DIRECT"),
                        Map.of("targetColumn", "EVENT_DATE", "sourceExpression", "EVENT_DATE", "transformType", "DIRECT"))));

        File db = DuckDbUtil.tempDbFile("test_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {

            // Simulate what the plugin ingester would create (pre-typed DATE column)
            st.execute("CREATE TABLE raw_CALL AS SELECT * FROM (VALUES " +
                    "('r1', 'CALL', DATE '2020-04-03', 0)," +
                    "('r2', 'CALL', DATE '2020-04-03', 0)) " +
                    "t(ID, EVENT_TYPE, EVENT_DATE, __src_id)");

            DataTransformer.materialize(conn, schema, cfg, "raw_CALL", "transformed_CALL");

            try (ResultSet rs = st.executeQuery(
                    "SELECT ID, EVENT_TYPE, event_type, year, month, day FROM transformed_CALL ORDER BY ID")) {
                assertTrue(rs.next());
                assertEquals("r1",   rs.getString("ID"));
                assertEquals("CALL", rs.getString("EVENT_TYPE"));
                assertEquals("CALL", rs.getString("event_type"));   // partition col
                assertEquals("2020", rs.getString("year"));
                assertEquals("04",   rs.getString("month"));
                assertEquals("03",   rs.getString("day"));
                assertTrue(rs.next());
                assertFalse(rs.next());
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Legacy partitionKey on a VARCHAR date column — backward compat unchanged. */
    @Test
    void legacyPartitionKeyStillWorks(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        // Mini schema with partitionKey (existing format, no partitions[])
        Map<String, Object> schema = PipelineConfigBatchTest.miniSchemaMap();

        File db = DuckDbUtil.tempDbFile("test_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES " +
                    "('a', 1.5, '2020-04-03', 0)) t(ID, AMT, EVENT_DATE, __src_id)");

            DataTransformer.materialize(conn, schema, cfg);   // backward-compat overload

            try (ResultSet rs = st.executeQuery("SELECT year, month, day FROM transformed")) {
                assertTrue(rs.next());
                assertEquals("2020", rs.getString("year"));
                assertEquals("04",   rs.getString("month"));
                assertEquals("03",   rs.getString("day"));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
