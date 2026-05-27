package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LineageCollectorTest {

    @Test
    void countsRowsPerSrcAndPartition() throws Exception {
        File db = DuckDbUtil.tempDbFile("test_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " +
                    "('2020','04','03',0)," +
                    "('2020','04','03',0)," +
                    "('2020','04','03',1)," +
                    "('2020','01','01',0)) t(year, month, day, __src_id)");

            List<PartitionOutput> outs = List.of(
                    new PartitionOutput("year=2020/month=04/day=03", "/db/B1_out.csv", 1),
                    new PartitionOutput("year=2020/month=01/day=01", "/db/B1_out.csv", 1));
            Map<Integer, String> srcIdToFile = Map.of(0, "a.csv", 1, "b.csv");

            List<LineageRow> rows = LineageCollector.collect(conn, "transformed", "B1", srcIdToFile, outs);

            // a.csv -> 04/03 = 2 rows ; b.csv -> 04/03 = 1 row ; a.csv -> 01/01 = 1 row
            long aTo0403 = rows.stream()
                    .filter(r -> r.inputFile().equals("a.csv") && r.partition().equals("year=2020/month=04/day=03"))
                    .mapToLong(LineageRow::rowCount).sum();
            assertEquals(2, aTo0403);
            long bTo0403 = rows.stream()
                    .filter(r -> r.inputFile().equals("b.csv") && r.partition().equals("year=2020/month=04/day=03"))
                    .mapToLong(LineageRow::rowCount).sum();
            assertEquals(1, bTo0403);
            assertEquals(3, rows.size());
            assertTrue(rows.stream().allMatch(r -> r.outputFile().equals("/db/B1_out.csv")));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
