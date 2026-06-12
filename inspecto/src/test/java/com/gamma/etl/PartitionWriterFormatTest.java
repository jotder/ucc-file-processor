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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link PartitionWriter}'s format/compression branches (PARQUET output +
 * compression clause) and verifies the {@code __src_id} column is excluded from
 * written output — branches the existing CSV-only tests miss (25% → higher).
 */
class PartitionWriterFormatTest {

    /** Build a transformed-style table with partition cols + the internal __src_id. */
    private static void seed(Statement st) throws Exception {
        st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " +
                "('r1','2020','04','03',0)," +
                "('r2','2020','04','03',0)," +
                "('r3','2020','04','04',1)) t(ID, year, month, day, __src_id)");
    }

    @Test
    void parquetWithCompressionRevealsPerPartitionFiles(@TempDir Path dir) throws Exception {
        String dbDir = dir.resolve("out").toString().replace("\\", "/");
        File db = DuckDbUtil.tempDbFile("pw_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            seed(st);
            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "PARQUET", "snappy", "base",
                    List.of("year", "month", "day"));

            // Two distinct day partitions → two output files.
            assertEquals(2, outs.size(), "expected one parquet file per day partition");
            for (PartitionOutput o : outs) {
                assertTrue(o.outputFile().endsWith(".parquet"), "parquet extension: " + o.outputFile());
                assertTrue(o.bytes() > 0, "non-empty output");
            }

            // Read the output back and confirm __src_id was excluded.
            try (ResultSet rs = st.executeQuery(
                    "SELECT * FROM read_parquet('" + dbDir + "/**/*.parquet')")) {
                var md = rs.getMetaData();
                boolean hasSrcId = false;
                for (int i = 1; i <= md.getColumnCount(); i++)
                    if ("__src_id".equalsIgnoreCase(md.getColumnName(i))) hasSrcId = true;
                assertFalse(hasSrcId, "__src_id must be excluded from written output");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void parquetWithoutCompressionStillWrites(@TempDir Path dir) throws Exception {
        String dbDir = dir.resolve("out2").toString().replace("\\", "/");
        File db = DuckDbUtil.tempDbFile("pw2_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            seed(st);
            // null compression exercises the false branch of the compression clause.
            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "PARQUET", null, "base",
                    List.of("year", "month", "day"));
            assertEquals(2, outs.size());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void defaultPartitionColumnsOverload(@TempDir Path dir) throws Exception {
        String dbDir = dir.resolve("out3").toString().replace("\\", "/");
        File db = DuckDbUtil.tempDbFile("pw3_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            seed(st);
            // 6-arg overload defaults to (year, month, day).
            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "CSV", null, "base");
            assertEquals(2, outs.size());
            long files;
            try (Stream<Path> s = java.nio.file.Files.walk(dir.resolve("out3"))) {
                files = s.filter(java.nio.file.Files::isRegularFile)
                         .filter(p -> p.toString().endsWith(".csv")).count();
            }
            assertEquals(2, files);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
