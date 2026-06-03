package com.gamma.etl;

import com.gamma.api.PublicApi;

import java.util.List;

/**
 * The framework-provided callback a {@link StreamingFileIngester} writes records into, one
 * record at a time. The ingester {@linkplain #emit emits} records as it decodes; the framework owns
 * all buffering, the DuckDB writes, transform, partitioned output, and lineage.
 *
 * <h3>Why this exists</h3>
 * Emitting record-by-record (rather than returning whole materialised tables) lets the framework
 * bound memory and scratch for inputs of any size: in generation mode it periodically flushes a
 * bounded "generation" to partitioned output, so a multi-hundred-GB / TB custom file (binary,
 * proprietary text, ASN.1, …) neither exhausts the JVM heap nor fills scratch with the full decoded
 * dataset — even for formats only the ingester knows how to split.
 *
 * <h3>Usage</h3>
 * <ol>
 *   <li>(Optional) call {@link #define(String, List)} once per segment key to declare the exact
 *       column names you will emit, in order. If you skip it, the framework derives the columns
 *       from that segment's {@code raw.fields} list — sufficient when every partition source column
 *       is also a raw field, but you <em>must</em> {@code define} explicitly when the schema's
 *       {@code partitions[]} reference an ingester-derived column not present in {@code raw.fields}
 *       (e.g. {@code EVENT_TYPE}).</li>
 *   <li>Call {@link #emit(String, Object...)} once per parsed record, passing the values
 *       positionally in the same order as the declared/derived columns. Values are stored as
 *       {@code VARCHAR} (matching the framework's CAST-at-transform-time model); {@code null} is
 *       written as SQL {@code NULL}. Do <em>not</em> pass a {@code __src_id} value — the framework
 *       adds that column itself.</li>
 *   <li>Call {@link #reject(String)} for a record of a known segment type that is malformed
 *       (counts toward {@code errorRows}); call {@link #junk()} for a record whose type is unknown
 *       and skipped (counts toward {@code junkCandidateRows}). These mirror the
 *       {@link IngestResult} counters and feed the same quarantine decision.</li>
 * </ol>
 *
 * <p>The sink is single-threaded: an ingester must call it only from the thread executing its
 * {@link StreamingFileIngester#ingest} method, and must not retain a reference past that call.
 *
 * @see StreamingFileIngester
 */
@PublicApi(since = "3.10.0")
public interface RecordSink {

    /**
     * Declare the column layout for {@code segmentKey} before emitting any record for it. Columns
     * are created as {@code VARCHAR} in declared order. Optional — when omitted, the framework
     * derives the columns from the segment's {@code raw.fields}. Must be called before the first
     * {@link #emit} for the key, and at most once per key.
     *
     * @param segmentKey a key declared in {@code processing.segments}
     * @param columns    ordered, non-empty list of column names (must not include {@code __src_id})
     */
    void define(String segmentKey, List<String> columns) throws Exception;

    /**
     * Emit one parsed record for {@code segmentKey}. {@code values} are positional and must match
     * the count and order of the declared/derived columns; each is stored as {@code VARCHAR}
     * ({@code null} ⇒ SQL {@code NULL}). The framework batches rows, flushes them to DuckDB, and
     * may write a bounded partition generation transparently.
     *
     * @param segmentKey a key declared in {@code processing.segments}
     * @param values     one value per column, in column order (no {@code __src_id})
     */
    void emit(String segmentKey, Object... values) throws Exception;

    /** Count a record of a known segment type that failed validation (→ {@code errorRows}). */
    default void reject(String segmentKey) throws Exception { }

    /** Count a record whose type is not a declared segment and was skipped (→ {@code junkCandidateRows}). */
    default void junk() throws Exception { }
}
