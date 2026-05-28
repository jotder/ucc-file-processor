package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LineageCollectorDynamicTest {

    @Test
    void buildsPartitionPathFromDynamicColumns() throws Exception {
        File db = DuckDbUtil.tempDbFile("test_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {

            st.execute("CREATE TABLE transformed_CALL AS SELECT * FROM (VALUES " +
                    "('CALL','2020','04','03',0)," +
                    "('CALL','2020','04','03',0)," +
                    "('CALL','2020','04','03',1)) " +
                    "t(event_type, year, month, day, __src_id)");

            List<PartitionOutput> outs = List.of(
                    new PartitionOutput("event_type=CALL/year=2020/month=04/day=03",
                            "/db/B1_out.parquet", 1));
            Map<Integer, String> srcMap = Map.of(0, "a.bin", 1, "b.bin");
            List<String> partCols = List.of("event_type", "year", "month", "day");

            List<LineageRow> rows = LineageCollector.collect(
                    conn, "transformed_CALL", "B1", srcMap, outs, partCols);

            assertEquals(2, rows.size());  // srcId 0 and 1

            LineageRow r0 = rows.stream().filter(r -> r.srcId() == 0).findFirst().orElseThrow();
            assertEquals(2, r0.rowCount());
            assertEquals("event_type=CALL/year=2020/month=04/day=03", r0.partition());
            assertEquals("a.bin", r0.inputFile());

            LineageRow r1 = rows.stream().filter(r -> r.srcId() == 1).findFirst().orElseThrow();
            assertEquals(1, r1.rowCount());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
