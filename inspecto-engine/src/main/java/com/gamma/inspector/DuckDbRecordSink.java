package com.gamma.inspector;

import com.gamma.etl.*;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.inspector.BatchIngestStrategy.dropTable;

/**
 * Framework-side {@link RecordSink} backing {@link StreamingPluginBatchStrategy}: the bridge that
 * turns a {@link StreamingFileIngester}'s record stream into bounded, partitioned output.
 *
 * <p>Two levels of buffering keep both heap <em>and</em> scratch bounded for an arbitrarily large
 * file:
 * <ul>
 *   <li><b>Append batching</b> — emitted rows accumulate in a small heap buffer ({@link #APPEND_BATCH}
 *       rows) then flush to the segment's {@code raw_<KEY>_f<srcId>} DuckDB table via one prepared
 *       statement. The heap never holds more than one batch.</li>
 *   <li><b>Generation flushing</b> — once a segment's raw table reaches {@code flushRows} rows, that
 *       generation is transformed → written to partitioned output → lineage-counted, then the raw
 *       table is dropped and recreated empty. Peak scratch stays ~one generation, not the whole
 *       file. Each generation writes its own per-partition file ({@code <stem>_gNNNNN_out.*}), which
 *       coexist in the partition dirs (valid Hive layout) — the same approach the CSV chunker uses.</li>
 * </ul>
 *
 * <p>The transform/write/lineage path is exactly the one {@link CsvBatchStrategy}/{@link
 * StreamingPluginBatchStrategy} use ({@link DataTransformer#materialize}, {@link PartitionWriter#write},
 * {@link LineageCollector#collect}) over the same {@code transformed_<KEY>} table shape, so output is
 * identical to the classic path — only the scheduling differs. The {@code __src_id} lineage tag is
 * carried as a trailing column on the raw table (the ingester does not supply it).
 *
 * <p>Failures during a generation flush are framework/schema errors, not file-decode errors, so they
 * are wrapped in {@link SinkFlushException} (a {@link RuntimeException}) to let the strategy fail the
 * batch rather than quarantine the input as unreadable.
 *
 * <p>Not thread-safe; used only from the thread running {@link StreamingFileIngester#ingest}.
 */
final class DuckDbRecordSink implements RecordSink, Closeable {

    private static final Logger log = LoggerFactory.getLogger(DuckDbRecordSink.class);

    /** Heap → DuckDB append batch size (rows). */
    private static final int APPEND_BATCH = 10_000;

    private final Connection conn;
    private final int        srcId;
    private final PipelineConfig cfg;
    private final String     batchId;
    private final String     fileStem;     // output basename stem (no extension)
    private final String     lineageName;  // file name recorded in lineage
    private final long       flushRows;    // generation budget (rows per raw table)
    private final boolean    unionMode;    // true → accumulate raw tables only; strategy unions + transforms

    private final Map<String, Seg> segs = new LinkedHashMap<>();

    private final List<PartitionOutput> outputs = new ArrayList<>();
    private final List<LineageRow>       lineage = new ArrayList<>();
    private long parsed;
    private long errors;
    private long junk;

    /** Per-segment streaming state. */
    private static final class Seg {
        final String key;
        final Map<String, Object> schema;
        final List<String> partCols;
        final String dbDir;
        List<String> columns;            // explicit (define) or derived (lazy)
        boolean created;
        DuckDBAppender appender;
        final List<Object[]> buffer = new ArrayList<>();
        long pendingInRaw;               // rows emitted since last generation flush
        int  genSeq;

        Seg(String key, Map<String, Object> schema, List<String> partCols, String dbDir) {
            this.key = key; this.schema = schema; this.partCols = partCols; this.dbDir = dbDir;
        }
    }

    /** Generation-mode sink (per-member bounded flushing). */
    DuckDbRecordSink(Connection conn, int srcId, PipelineConfig cfg, String batchId,
                     String fileStem, String lineageName, long flushRows) {
        this(conn, srcId, cfg, batchId, fileStem, lineageName, flushRows, false);
    }

    /**
     * @param unionMode when {@code true} the sink only populates {@code raw_<KEY>_f<srcId>} tables and
     *                  never generation-flushes or transforms — the strategy unions across members and
     *                  runs a single transform/write/lineage per segment.
     */
    DuckDbRecordSink(Connection conn, int srcId, PipelineConfig cfg, String batchId,
                     String fileStem, String lineageName, long flushRows, boolean unionMode) {
        this.conn = conn;
        this.srcId = srcId;
        this.cfg = cfg;
        this.batchId = batchId;
        this.fileStem = fileStem;
        this.lineageName = lineageName;
        this.unionMode = unionMode;
        this.flushRows = flushRows > 0 ? flushRows : Long.MAX_VALUE;
        for (Map.Entry<String, Map<String, Object>> e : cfg.schemas().segments().entrySet()) {
            String key = e.getKey();
            Map<String, Object> schema = e.getValue();
            List<PartitionDef> pd = PartitionDef.fromSchema(schema);
            List<String> partCols = pd.isEmpty()
                    ? List.of("year", "month", "day")
                    : PartitionDef.columnNames(pd);
            String dbDir = Paths.get(cfg.dirs().database(), key).toString();
            segs.put(key, new Seg(key, schema, partCols, dbDir));
        }
    }

    // ── RecordSink ──────────────────────────────────────────────────────────────

    @Override
    public void define(String segmentKey, List<String> columns) {
        Seg s = require(segmentKey);
        if (s.created)
            throw new IllegalStateException("define(\"" + segmentKey + "\") called after emit");
        if (columns == null || columns.isEmpty())
            throw new IllegalArgumentException("columns for segment '" + segmentKey + "' must be non-empty");
        s.columns = List.copyOf(columns);
    }

    @Override
    public void emit(String segmentKey, Object... values) {
        Seg s = require(segmentKey);
        ensureCreated(s, values.length);
        Object[] row = new Object[values.length];
        for (int i = 0; i < values.length; i++)
            row[i] = values[i] == null ? null : String.valueOf(values[i]);
        s.buffer.add(row);
        parsed++;
        s.pendingInRaw++;
        if (s.buffer.size() >= APPEND_BATCH) appendFlush(s);
        if (!unionMode && s.pendingInRaw >= flushRows) generationFlush(s);
    }

    @Override public void reject(String segmentKey) { require(segmentKey); errors++; }
    @Override public void junk() { junk++; }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /** Final flush of every segment's residual rows. Call once after the ingester returns. */
    void finish() {
        for (Seg s : segs.values()) {
            appendFlush(s);
            if (unionMode) {
                // Union mode: leave the populated raw table in place; the strategy unions + transforms.
                closeAppender(s);
            } else if (s.pendingInRaw > 0) {
                generationFlush(s);
            }
        }
    }

    /**
     * Union-mode accessor: segment key → populated {@code raw_<KEY>_f<srcId>} table name, for every
     * segment that received at least one row. The strategy unions these across members.
     */
    Map<String, String> rawTables() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Seg s : segs.values())
            if (s.created && s.pendingInRaw > 0) out.put(s.key, raw(s));
        return out;
    }

    @Override
    public void close() {
        for (Seg s : segs.values()) closeAppender(s);
    }

    private static void closeAppender(Seg s) {
        if (s.appender != null) {
            try { s.appender.close(); } catch (Exception ignored) { }
            s.appender = null;
        }
    }

    // ── accessors (read after finish()) ─────────────────────────────────────────

    List<PartitionOutput> outputs() { return outputs; }
    List<LineageRow>      lineage() { return lineage; }
    long parsedRows() { return parsed; }
    long errorRows()  { return errors; }
    long junkRows()   { return junk; }

    // ── internals ───────────────────────────────────────────────────────────────

    private Seg require(String key) {
        Seg s = segs.get(key);
        if (s == null)
            throw new IllegalArgumentException(
                    "unknown segment '" + key + "'; declared segments: " + segs.keySet());
        return s;
    }

    private void ensureCreated(Seg s, int valueCount) {
        if (s.created) {
            if (valueCount != s.columns.size())
                throw new IllegalArgumentException(String.format(
                        "segment '%s' emit has %d value(s) but %d column(s) were declared",
                        s.key, valueCount, s.columns.size()));
            return;
        }
        if (s.columns == null) s.columns = deriveColumns(s);
        if (valueCount != s.columns.size())
            throw new IllegalArgumentException(String.format(
                    "segment '%s' emit has %d value(s) but %d column(s) (%s)",
                    s.key, valueCount, s.columns.size(), s.columns));
        String table = raw(s);
        StringBuilder ddl = new StringBuilder("CREATE TABLE \"").append(table).append("\" (");
        for (String c : s.columns) ddl.append('"').append(c).append("\" VARCHAR, ");
        ddl.append("\"__src_id\" INTEGER)");
        try (Statement st = conn.createStatement()) {
            st.execute(ddl.toString());
            s.appender = ((DuckDBConnection) conn).createAppender("", table);
        } catch (Exception e) {
            throw new SinkFlushException("CREATE TABLE failed for " + table, e);
        }
        s.created = true;
    }

    @SuppressWarnings("unchecked")
    private List<String> deriveColumns(Seg s) {
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) s.schema.get("raw")).get("fields");
        List<String> cols = new ArrayList<>(fields.size());
        for (Map<String, Object> f : fields) cols.add((String) f.get("name"));
        return cols;
    }

    private void appendFlush(Seg s) {
        if (s.buffer.isEmpty()) return;
        try {
            int n = s.columns.size();
            for (Object[] row : s.buffer) {
                s.appender.beginRow();
                for (int i = 0; i < n; i++) s.appender.append((String) row[i]);
                s.appender.append(srcId);
                s.appender.endRow();
            }
            s.appender.flush();
            s.buffer.clear();
        } catch (Exception e) {
            throw new SinkFlushException("INSERT failed for " + raw(s), e);
        }
    }

    /** Transform → write → lineage for the rows currently in the raw table, then reset it. */
    private void generationFlush(Seg s) {
        appendFlush(s);
        if (s.pendingInRaw == 0) return;
        // Close the appender so all rows are committed and visible before the transform query reads them.
        closeAppender(s);
        String raw  = raw(s);
        String dest = "transformed_" + s.key;
        try {
            dropTable(conn, dest);
            DataTransformer.materialize(conn, s.schema, cfg, raw, dest);
            String baseName = fileStem + "_g" + String.format("%05d", s.genSeq);
            List<PartitionOutput> outs = PartitionWriter.write(conn, dest, s.dbDir,
                    cfg.output().format(), cfg.output().compression(), baseName, s.partCols);
            List<LineageRow> lin = LineageCollector.collect(conn, dest, batchId,
                    Map.of(srcId, lineageName), outs, s.partCols);
            outputs.addAll(outs);
            lineage.addAll(lin);
            dropTable(conn, dest);
        } catch (SinkFlushException e) {
            throw e;
        } catch (Exception e) {
            throw new SinkFlushException("generation flush failed for segment '" + s.key + "'", e);
        }
        // Free the generation's scratch and start the next one clean.
        dropTable(conn, raw);
        s.created = false;
        s.pendingInRaw = 0;
        s.genSeq++;
        log.debug("[STREAM] segment {} flushed generation {} ({} rows)", s.key, s.genSeq - 1, flushRows);
    }

    private String raw(Seg s) { return "raw_" + s.key + "_f" + srcId; }
}
