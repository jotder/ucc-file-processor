package com.gamma.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PartitionWriter;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.JsonAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Durable, append-only event store backed by <b>rolling Hive-partitioned Parquet</b>, queried by
 * DuckDB — the answer to "can rolling Parquet be the database?" for the immutable EVENT layer
 * (see {@code docs/superpowers/specs/2026-06-13-operational-intelligence-roadmap.md} §0).
 *
 * <h3>Why this works (and where it doesn't)</h3>
 * Events are append-only and never modified, which is exactly Parquet's sweet spot. We do <b>not</b>
 * write one file per event (the small-files anti-pattern); instead events are buffered in memory and
 * <b>flushed in batches</b> to Parquet, partitioned by {@code level/year/month/day}. Reads glob the
 * directory through {@code read_parquet(..., hive_partitioning=true)} — the same idiom the enrichment
 * engine and KPI oracle already use ({@link SqlViews}). Retention is a file delete, not a SQL DELETE.
 * In-place updates are impossible here — fine for immutable events, which is why mutable operational
 * objects (Phase 2+) use a table store instead.
 *
 * <h3>Live tail</h3>
 * The newest events have not been flushed yet, so {@link #recent(int)} serves them from an in-memory
 * ring, and {@link #query(EventQuery)} merges the unflushed buffer with the on-disk Parquet so a search
 * never misses the most recent facts.
 *
 * <h3>Threading</h3>
 * A single DuckDB {@link Connection} (not thread-safe) is shared for both the flush {@code COPY} and
 * the {@code read_parquet} queries, so every public method is {@code synchronized}. Event volume is
 * modest relative to ingest, and flushes are batched, so the single lock is not a bottleneck.
 *
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public final class ParquetEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(ParquetEventStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Scratch table the buffer is staged into before each partitioned {@code COPY}. */
    private static final String BUF_TABLE = "evt_buf";

    /** Flush when this many events are buffered. */
    public static final int DEFAULT_FLUSH_THRESHOLD = 1000;
    /** Flush when the oldest buffered event is older than this, even below the size threshold. */
    public static final long DEFAULT_ROLL_MILLIS = 10_000L;

    private final Path root;
    private final int flushThreshold;
    private final long rollMillis;
    private final Connection conn;

    /** Unflushed events, oldest-first (write order). */
    private final Deque<Event> buffer = new ArrayDeque<>();
    /** Live-tail ring, newest-first; retained across flushes for {@link #recent(int)}. */
    private final InMemoryEventStore tail;
    /** Wall-clock when the current buffer started filling (for time-roll). */
    private long bufferOpenedAt;
    /** Monotonic flush sequence — keeps each flush's output filenames unique (no overwrite). */
    private long flushSeq;
    private boolean closed;

    /** Open a store under {@code dir} with default flush/roll/tail settings. */
    public static ParquetEventStore open(Path dir) {
        return new ParquetEventStore(dir, DEFAULT_FLUSH_THRESHOLD, DEFAULT_ROLL_MILLIS,
                InMemoryEventStore.DEFAULT_CAPACITY);
    }

    public ParquetEventStore(Path dir, int flushThreshold, long rollMillis, int tailCapacity) {
        this.root = dir.toAbsolutePath();
        this.flushThreshold = Math.max(1, flushThreshold);
        this.rollMillis = Math.max(0, rollMillis);
        this.tail = new InMemoryEventStore(tailCapacity);
        try {
            Files.createDirectories(root);
            DuckDbUtil.loadDriver();
            this.conn = DriverManager.getConnection("jdbc:duckdb:");   // in-memory scratch + reader
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE " + BUF_TABLE + " ("
                        + "event_id VARCHAR, ts_ms BIGINT, type VARCHAR, source VARCHAR, "
                        + "pipeline VARCHAR, correlation_id VARCHAR, message VARCHAR, attributes VARCHAR, "
                        + "payload VARCHAR, "
                        + "level VARCHAR, year VARCHAR, month VARCHAR, day VARCHAR)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise Parquet event store at " + root, e);
        }
    }

    // ── append + flush ──────────────────────────────────────────────────────────

    @Override
    public synchronized void append(Event event) {
        if (event == null || closed) return;
        tail.append(event);
        if (buffer.isEmpty()) bufferOpenedAt = System.currentTimeMillis();
        buffer.addLast(event);
        boolean full = buffer.size() >= flushThreshold;
        boolean stale = rollMillis > 0 && System.currentTimeMillis() - bufferOpenedAt >= rollMillis;
        if (full || stale) flushLocked();
    }

    @Override
    public synchronized void flush() {
        flushLocked();
    }

    /** Write the buffer to a fresh partitioned Parquet file set, then clear it. Caller holds the lock. */
    private void flushLocked() {
        if (buffer.isEmpty()) return;
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + BUF_TABLE + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (Event e : buffer) {
                    LocalDate d = Instant.ofEpochMilli(e.ts()).atZone(ZoneOffset.UTC).toLocalDate();
                    int i = 1;
                    ps.setString(i++, e.eventId());
                    ps.setLong(i++, e.ts());
                    ps.setString(i++, e.type());
                    ps.setString(i++, e.source());
                    ps.setString(i++, e.pipeline());
                    ps.setString(i++, e.correlationId());
                    ps.setString(i++, e.message());
                    ps.setString(i++, JSON.writeValueAsString(e.attributes()));
                    ps.setString(i++, JSON.writeValueAsString(e.payload()));
                    ps.setString(i++, e.level().name());
                    ps.setString(i++, String.format("%04d", d.getYear()));
                    ps.setString(i++, String.format("%02d", d.getMonthValue()));
                    ps.setString(i, String.format("%02d", d.getDayOfMonth()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            String baseName = "events_" + System.currentTimeMillis() + "_" + (flushSeq++);
            PartitionWriter.write(conn, BUF_TABLE, root.toString(), "PARQUET", "zstd", baseName,
                    List.of("level", "year", "month", "day"), List.of());
        } catch (Exception e) {
            // Never propagate to the caller (often the log appender). The events survive in the live-tail
            // ring; we drop the buffer to avoid unbounded growth on a persistent failure.
            log.warn("Event flush to Parquet failed, dropping {} buffered event(s): {}",
                    buffer.size(), e.getMessage());
        } finally {
            buffer.clear();
            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM " + BUF_TABLE);
            } catch (SQLException ignore) { /* best effort */ }
        }
    }

    // ── reads ─────────────────────────────────────────────────────────────────────

    @Override
    public synchronized List<Event> recent(int limit) {
        return tail.recent(limit);
    }

    @Override
    public synchronized List<Event> query(EventQuery q) {
        List<Event> merged = new ArrayList<>();
        // 1) unflushed buffer (newest facts) — filter in memory
        for (Event e : buffer) if (q.matches(e)) merged.add(e);
        // 2) on-disk Parquet — filter + page in SQL (skip when nothing has been flushed yet)
        if (hasParquet()) merged.addAll(queryParquet(q));
        merged.sort(Comparator.comparingLong(Event::ts).reversed());
        int from = Math.min(q.offset(), merged.size());
        int to = Math.min(from + q.limit(), merged.size());
        return new ArrayList<>(merged.subList(from, to));
    }

    private List<Event> queryParquet(EventQuery q) {
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.fromMs() != null) { conds.add("ts_ms >= ?"); params.add(q.fromMs()); }
        if (q.toMs() != null) { conds.add("ts_ms <= ?"); params.add(q.toMs()); }
        if (q.minLevel() != null) conds.add("level IN (" + levelInList(q.minLevel()) + ")");
        if (q.type() != null) { conds.add("type = ?"); params.add(q.type()); }
        if (q.pipeline() != null) { conds.add("lower(pipeline) = lower(?)"); params.add(q.pipeline()); }
        if (q.correlationId() != null) { conds.add("correlation_id = ?"); params.add(q.correlationId()); }
        if (q.textContains() != null) {
            conds.add("(lower(message) LIKE ? OR lower(source) LIKE ?)");
            String like = "%" + q.textContains().toLowerCase(java.util.Locale.ROOT) + "%";
            params.add(like); params.add(like);
        }
        String reader = SqlViews.reader("PARQUET", root + "/**/*.parquet", true);
        String where = conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
        String sql = "SELECT event_id, ts_ms, level, type, source, pipeline, correlation_id, message, attributes, payload"
                + " FROM " + reader + where + " ORDER BY ts_ms DESC LIMIT ?";
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (Object p : params) {
                if (p instanceof Long l) ps.setLong(i++, l);
                else ps.setString(i++, String.valueOf(p));
            }
            ps.setInt(i, q.offset() + q.limit());      // fetch enough to page after merge with buffer
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Event(rs.getString("event_id"), rs.getLong("ts_ms"),
                            EventLevel.parse(rs.getString("level")), rs.getString("type"),
                            rs.getString("source"), rs.getString("pipeline"),
                            rs.getString("correlation_id"), rs.getString("message"),
                            JsonAttributes.fromJson(rs.getString("attributes")),
                            JsonAttributes.fromPayloadJson(rs.getString("payload"))));
                }
            }
        } catch (SQLException e) {
            log.warn("Event Parquet query failed: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public synchronized List<Event> page(int limit, Long afterTs, String afterId) {
        int n = Math.max(0, limit);
        List<Event> merged = new ArrayList<>();
        // 1) unflushed buffer (newest facts) — keyset-filter in memory
        for (Event e : buffer) if (EventStore.afterKey(e, afterTs, afterId)) merged.add(e);
        // 2) on-disk Parquet — keyset predicate + order in SQL (skip when nothing has been flushed yet)
        if (hasParquet()) merged.addAll(pageParquet(n, afterTs, afterId));
        merged.sort(KEYSET_ORDER);
        return new ArrayList<>(merged.subList(0, Math.min(n, merged.size())));
    }

    /** One SQL keyset page over the Parquet files: strictly older than {@code (afterTs, afterId)}, newest-first. */
    private List<Event> pageParquet(int limit, Long afterTs, String afterId) {
        String reader = SqlViews.reader("PARQUET", root + "/**/*.parquet", true);
        String where = afterTs == null ? ""
                : " WHERE (ts_ms < ? OR (ts_ms = ? AND event_id < ?))";
        String sql = "SELECT event_id, ts_ms, level, type, source, pipeline, correlation_id, message, attributes, payload"
                + " FROM " + reader + where + " ORDER BY ts_ms DESC, event_id DESC LIMIT ?";
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (afterTs != null) {
                ps.setLong(i++, afterTs);
                ps.setLong(i++, afterTs);
                ps.setString(i++, afterId == null ? "" : afterId);
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Event(rs.getString("event_id"), rs.getLong("ts_ms"),
                            EventLevel.parse(rs.getString("level")), rs.getString("type"),
                            rs.getString("source"), rs.getString("pipeline"),
                            rs.getString("correlation_id"), rs.getString("message"),
                            JsonAttributes.fromJson(rs.getString("attributes")),
                            JsonAttributes.fromPayloadJson(rs.getString("payload"))));
                }
            }
        } catch (SQLException e) {
            log.warn("Event Parquet page failed: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public synchronized long count() {
        long total = buffer.size();
        if (!hasParquet()) return total;
        String reader = SqlViews.reader("PARQUET", root + "/**/*.parquet", true);
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + reader);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) total += rs.getLong(1);
        } catch (SQLException e) {
            log.warn("Event Parquet count failed: {}", e.getMessage());
        }
        return total;
    }

    /** Comma-separated quoted level names at or above {@code min} — values are enum names, so safe to inline. */
    private static String levelInList(EventLevel min) {
        List<String> names = new ArrayList<>();
        for (EventLevel l : EventLevel.values()) if (l.atLeast(min)) names.add("'" + l.name() + "'");
        return String.join(", ", names);
    }

    /** {@code true} when at least one Parquet file exists under the root (else read_parquet would error). */
    private boolean hasParquet() {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> w = Files.walk(root)) {
            return w.anyMatch(p -> p.getFileName().toString().endsWith(".parquet"));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        flushLocked();
        closed = true;
        try { conn.close(); } catch (SQLException e) { log.warn("Error closing event store: {}", e.getMessage()); }
    }
}
