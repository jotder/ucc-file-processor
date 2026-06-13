package com.gamma.event;

import java.util.List;

/**
 * Append-only sink + query surface for {@link Event}s — the "Event Engine" storage seam of the
 * Operational Intelligence Platform. Two implementations sit behind it: {@code InMemoryEventStore}
 * (a bounded ring; the lean default and the live-tail buffer) and {@code ParquetEventStore} (durable
 * rolling Hive-partitioned Parquet, queried via DuckDB {@code read_parquet}). The Control API and
 * {@link EventLog} depend only on this interface, so the backend is a deployment choice
 * ({@code -Devents.backend=memory|parquet}).
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li><b>Append-only.</b> There is intentionally no update or delete — events are immutable facts.
 *       Retention is the implementation's concern (dropping old ring entries / old Parquet files).</li>
 *   <li>{@link #query(EventQuery)} returns matching events <b>newest-first</b>, honoring the query's
 *       {@code limit}/{@code offset}.</li>
 *   <li>{@link #recent(int)} is the live-tail fast path: the newest {@code limit} events, newest-first.</li>
 *   <li>Implementations must be thread-safe: events arrive from many threads (ingest workers, the log
 *       appender, the bus).</li>
 * </ul>
 *
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public interface EventStore extends AutoCloseable {

    /** Append one immutable event. Never blocks on durability — see {@link #flush()}. */
    void append(Event event);

    /** Matching events, newest-first, paged per {@code query}. */
    List<Event> query(EventQuery query);

    /** The newest {@code limit} events, newest-first (the live-tail fast path). */
    List<Event> recent(int limit);

    /**
     * Force any buffered events to durable storage. No-op for purely in-memory stores; for
     * {@code ParquetEventStore} this flushes the in-memory buffer to a Parquet file.
     */
    default void flush() {}

    /** Flush and release resources (e.g. the DuckDB connection). Idempotent. */
    @Override
    default void close() {}
}
