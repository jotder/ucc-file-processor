package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PartitionWriterTest {

    @Test
    void writesPartitionsAndExcludesSrcId(@TempDir Path dir) throws Exception {
        File db = DuckDbUtil.tempDbFile("test_");
        String dbDir = dir.resolve("out").toString();
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " +
                    "('a', '2020', '04', '03', 0)," +
                    "('b', '2020', '04', '03', 1)," +
                    "('c', '2020', '01', '01', 0)) " +
                    "t(ID, year, month, day, __src_id)");

            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "CSV", null, "B1");

            assertEquals(2, outs.size());                       // two partitions
            for (PartitionOutput o : outs) {
                assertTrue(o.outputFile().endsWith("B1_out.csv"));
                String content = Files.readString(Path.of(o.outputFile()));
                assertFalse(content.contains("__src_id"));      // excluded
                assertTrue(content.contains("ID") || content.contains("a") || content.contains("c"));
            }
            try (Stream<Path> w = Files.walk(Path.of(dbDir))) {
                assertTrue(w.anyMatch(p -> p.toString().replace('\\','/').contains("year=2020/month=04/day=03")));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void revealsManyPartitionsInParallelWithoutLoss(@TempDir Path dir) throws Exception {
        // 40 distinct day partitions exceeds REVEAL_PARALLEL_THRESHOLD, so the reveal
        // fans out across the common pool. Every partition must still be revealed under
        // its stable name with no collisions or lost rows.
        File db = DuckDbUtil.tempDbFile("test_par_");
        String dbDir = dir.resolve("out").toString();
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            // 40 rows, each its own day → 40 partitions; row id == day so we can verify.
            st.execute("CREATE TABLE transformed AS SELECT " +
                    "  'row_' || d AS ID, '2020' AS year, '01' AS month, " +
                    "  lpad(CAST(d AS VARCHAR), 2, '0') AS day, 0 AS __src_id " +
                    "FROM range(1, 41) t(d)");

            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "CSV", null, "P");

            assertEquals(40, outs.size(), "one output per day partition");
            // Distinct partition paths (no two staged files collapsed onto one name).
            long distinctPartitions = outs.stream().map(PartitionOutput::partition).distinct().count();
            assertEquals(40, distinctPartitions);
            for (PartitionOutput o : outs) {
                assertTrue(o.outputFile().endsWith("P_out.csv"), o.outputFile());
                assertTrue(o.bytes() > 0, "non-empty: " + o.outputFile());
            }
            // No leftover .tmp files from the two-step reveal.
            try (Stream<Path> w = Files.walk(Path.of(dbDir))) {
                assertFalse(w.anyMatch(p -> p.toString().endsWith(".tmp")), "no stray temp files");
            }
            // Every revealed file holds exactly its one row; 40 rows total survive.
            int total = 0;
            for (PartitionOutput o : outs)
                total += Files.readAllLines(Path.of(o.outputFile())).size() - 1; // minus header
            assertEquals(40, total, "all rows conserved across parallel reveal");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
