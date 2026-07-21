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
 * <p>Runs one {@code GROUP BY __src_id, <partitionColumns>} over the materialized
 * table and joins each partition-key combination to its {@link PartitionOutput} file.
 */
public final class LineageCollector {

    private LineageCollector() {}

    private static final List<String> DEFAULT_PARTITION_COLS = List.of("year", "month", "day");

    /**
     * Backward-compatible overload — assumes {@code (year, month, day)} partition columns.
     *
     * @param conn        connection containing {@code table}
     * @param table       materialized table (must contain {@code year,month,day,__src_id})
     * @param batchId     owning batch id
     * @param srcIdToFile map of {@code __src_id} → member file name
     * @param outputs     revealed partition outputs (partition path → file)
     * @return one {@link LineageRow} per (src, partition) group that has rows
     */
    public static List<LineageRow> collect(Connection conn, String table, String batchId,
                                           Map<Integer, String> srcIdToFile,
                                           List<PartitionOutput> outputs) throws SQLException {
        return collect(conn, table, batchId, srcIdToFile, outputs, DEFAULT_PARTITION_COLS);
    }

    /**
     * Collect lineage using an explicit ordered list of partition column names.
     *
     * @param partitionColumns the same columns passed to {@link PartitionWriter#write};
     *                         must be present in {@code table}
     */
    public static List<LineageRow> collect(Connection conn, String table, String batchId,
                                           Map<Integer, String> srcIdToFile,
                                           List<PartitionOutput> outputs,
                                           List<String> partitionColumns) throws SQLException {
        Map<String, String> partToFile = new HashMap<>();
        for (PartitionOutput o : outputs) partToFile.put(o.partition(), o.outputFile());

        // SELECT __src_id, col1, col2, …, COUNT(*) AS n FROM "table" GROUP BY 1, 2, …, N+1
        StringBuilder sql = new StringBuilder("SELECT __src_id");
        for (String col : partitionColumns) sql.append(", \"").append(col).append('"');
        sql.append(", COUNT(*) AS n FROM \"").append(table).append("\" GROUP BY 1");
        for (int i = 0; i < partitionColumns.size(); i++) sql.append(", ").append(i + 2);

        List<LineageRow> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) {
                int           srcId    = rs.getInt(1);
                // Build Hive-style partition path from column values, e.g.
                // "event_type=CALL/year=2020/month=04/day=03"
                StringBuilder path    = new StringBuilder();
                for (int i = 0; i < partitionColumns.size(); i++) {
                    if (i > 0) path.append('/');
                    path.append(partitionColumns.get(i)).append('=').append(rs.getString(i + 2));
                }
                String partition  = path.toString();
                String outputFile = partToFile.getOrDefault(partition, "");
                long   n          = rs.getLong(partitionColumns.size() + 2);
                rows.add(new LineageRow(batchId, srcId,
                        srcIdToFile.getOrDefault(srcId, ""), outputFile, partition, n));
            }
        }
        return rows;
    }
}
