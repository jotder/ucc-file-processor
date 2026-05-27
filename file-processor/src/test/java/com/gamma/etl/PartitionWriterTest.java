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
}
