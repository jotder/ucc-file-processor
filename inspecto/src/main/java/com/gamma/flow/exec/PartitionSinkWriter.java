package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowStores;
import com.gamma.sql.SqlViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>T32 Phase A — the real sink write for a flow job.</b> A {@link FlowExecutor.SinkWriter} that
 * persists each committed sink branch's relation to its declared {@code store} under
 * {@code <dataDir>/<store>}, reusing the production {@link PartitionWriter} (the same idempotent,
 * {@code OVERWRITE_OR_IGNORE} Hive-partitioned write the legacy engine and the enrichment engine use).
 *
 * <p>A sink declares its {@code store}, {@code format} ({@code PARQUET}/{@code CSV}, default Parquet),
 * optional {@code compression}, and optional {@code partitions}. When partitions are declared the rows
 * are written Hive-partitioned via {@link PartitionWriter}; when none are declared the branch is written
 * as a single unpartitioned file (the legacy {@code PartitionWriter} always partitions, so this writer
 * owns the unpartitioned case rather than forcing a {@code (year,month,day)} default onto a store that
 * may not have those columns).
 *
 * <p>{@code sink.view} subtypes ({@link FlowStores.Produced#restsOnDisk() non-resting}) write no bytes
 * in Phase A — a logical view's catalog/DuckDB-view registration is Phase C; such a sink is skipped here.
 */
@PublicApi(since = "4.3.0")
public final class PartitionSinkWriter implements FlowExecutor.SinkWriter {

    private static final Logger log = LoggerFactory.getLogger(PartitionSinkWriter.class);

    private final Connection conn;
    private final String dataDir;
    private final String baseName;
    private final List<PartitionOutput> outputs = new ArrayList<>();
    private long totalRows = 0L;

    /**
     * @param conn     the DuckDB connection holding each sink branch's input relation
     * @param dataDir  the data root under which each store is written as a sub-directory
     * @param baseName the output file stem ({@code <baseName>_out.<ext>}); typically the flow/job id
     */
    public PartitionSinkWriter(Connection conn, String dataDir, String baseName) {
        this.conn = conn;
        this.dataDir = dataDir;
        this.baseName = baseName;
    }

    @Override
    public void write(FlowNode sink, String inputTable) throws Exception {
        if (sink.type().endsWith(".view")) {                       // logical store — no bytes (Phase C)
            log.info("[FLOWJOB] skipping non-persistent sink '{}' ({}) — sink.view byte-write is Phase C",
                    sink.id(), sink.type());
            return;
        }
        Object storeCfg = sink.cfg(FlowStores.CONFIG_STORE);
        if (storeCfg == null || storeCfg.toString().isBlank())
            throw new IllegalStateException("sink '" + sink.id() + "' declares no '"
                    + FlowStores.CONFIG_STORE + "' to write to");
        String store = storeCfg.toString();
        String format = upper(sink.cfg("format"), "PARQUET");
        String compression = str(sink.cfg("compression"));
        List<String> partCols = partitionColumns(sink);
        String dir = dataDir.replace("\\", "/") + "/" + store;

        List<PartitionOutput> outs = partCols.isEmpty()
                ? writeUnpartitioned(inputTable, dir, format, compression)
                : PartitionWriter.write(conn, inputTable, dir, format, compression, baseName, partCols, List.of());
        outputs.addAll(outs);
        totalRows += count(inputTable);
        log.info("[FLOWJOB] sink '{}' → store '{}': {} file(s){}",
                sink.id(), store, outs.size(), partCols.isEmpty() ? " (unpartitioned)" : " partitioned by " + partCols);
    }

    /** Partition files written across every sink branch (one entry per file). */
    public List<PartitionOutput> outputs() { return List.copyOf(outputs); }

    /** Total rows written across every sink branch. */
    public long totalRows() { return totalRows; }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Single-file write when a sink declares no partitions (the legacy writer always partitions). */
    private List<PartitionOutput> writeUnpartitioned(String inputTable, String dir,
                                                     String format, String compression) throws Exception {
        Files.createDirectories(Path.of(dir));
        String file = dir + "/" + baseName + "_out." + SqlViews.ext(format);
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM \"" + inputTable + "\") TO '" + file
                    + "' (" + copyOptions(format, compression) + ")");
        }
        long bytes = Files.exists(Path.of(file)) ? Files.size(Path.of(file)) : 0L;
        return List.of(new PartitionOutput("", file, bytes));
    }

    private static String copyOptions(String format, String compression) {
        return switch (format) {
            case "PARQUET" -> "FORMAT PARQUET, COMPRESSION "
                    + (compression == null || compression.isBlank() ? "SNAPPY" : compression);
            case "CSV" -> "FORMAT CSV, HEADER true";
            default -> throw new IllegalArgumentException("Unsupported sink format: " + format);
        };
    }

    /** The sink's {@code partitions} as an ordered column list ({@code []} when absent). */
    private static List<String> partitionColumns(FlowNode sink) {
        Object p = sink.cfg("partitions");
        List<String> cols = new ArrayList<>();
        if (p instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("column") != null) cols.add(m.get("column").toString());
                else if (o != null && !o.toString().isBlank()) cols.add(o.toString());
            }
        }
        return cols;
    }

    private long count(String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String upper(Object o, String fallback) {
        return o == null || o.toString().isBlank() ? fallback : o.toString().toUpperCase();
    }
}
