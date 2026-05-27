package com.gamma.etl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * Builds the many-to-many count matrix for a batch: how many transformed rows
 * each member ({@code __src_id}) contributed to each output file.
 *
 * <p>Runs one {@code GROUP BY __src_id, year, month, day} over the materialized
 * {@code transformed} table and joins each {@code (year,month,day)} partition to
 * its {@link PartitionOutput} file.
 */
public final class LineageCollector {

    private LineageCollector() {}

    /**
     * @param conn        connection containing {@code table}
     * @param table       materialized table (must contain {@code year,month,day,__src_id})
     * @param batchId     owning batch id
     * @param srcIdToFile map of {@code __src_id} → member file name
     * @param outputs     revealed partition outputs (partition → file)
     * @return one {@link LineageRow} per (src, partition) group that has rows
     */
    public static List<LineageRow> collect(Connection conn, String table, String batchId,
                                           Map<Integer, String> srcIdToFile,
                                           List<PartitionOutput> outputs) throws SQLException {
        Map<String, String> partToFile = new HashMap<>();
        for (PartitionOutput o : outputs) partToFile.put(o.partition(), o.outputFile());

        List<LineageRow> rows = new ArrayList<>();
        String sql = "SELECT __src_id, year, month, day, COUNT(*) AS n FROM \""
                + table + "\" GROUP BY 1, 2, 3, 4";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                int    srcId     = rs.getInt(1);
                String partition = "year=" + rs.getString(2)
                        + "/month=" + rs.getString(3) + "/day=" + rs.getString(4);
                String outputFile = partToFile.getOrDefault(partition, "");
                long   n          = rs.getLong(5);
                rows.add(new LineageRow(batchId, srcId,
                        srcIdToFile.getOrDefault(srcId, ""), outputFile, partition, n));
            }
        }
        return rows;
    }
}
