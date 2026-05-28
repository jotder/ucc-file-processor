package com.gamma.etl;

import java.io.File;
import java.sql.Connection;
import java.util.List;

/**
 * Contract for custom file ingesters that produce one or more typed event streams
 * from a single input file.
 *
 * <h3>Responsibilities of the implementation</h3>
 * <ul>
 *   <li>Parse the file and populate one DuckDB table per logical event type.</li>
 *   <li>Table names <em>must</em> follow the convention {@code raw_<KEY>_f<srcId>},
 *       where {@code KEY} matches an entry in {@code cfg.segmentSchemas} and
 *       {@code srcId} is the member's position within the batch.</li>
 *   <li>Each table's columns must include all fields declared in the segment's
 *       {@code raw.fields} list, plus any ingester-computed columns referenced by
 *       the segment's {@code partitions[]} (e.g. {@code EVENT_TYPE VARCHAR},
 *       {@code CALL_DATE DATE}).  These derived columns are added as extra columns
 *       alongside the event-payload columns — no framework plumbing is needed for
 *       them (option A).</li>
 *   <li>Return one {@link Segment} per table created. If a segment has no rows it
 *       is still reported so the framework can record 0-row audit entries.</li>
 * </ul>
 *
 * <h3>Error handling</h3>
 * <ul>
 *   <li>Throw {@link java.io.IOException} if the file cannot be opened or read
 *       (framework quarantines as {@code QUARANTINED_UNREADABLE}).</li>
 *   <li>Silently skip or count individual malformed records; report the counts via
 *       {@link IngestResult} (framework quarantines as {@code QUARANTINED_MISMATCH}
 *       if all segments across the file have 0 {@code parsedRows}).</li>
 * </ul>
 *
 * <h3>Registration</h3>
 * Reference the implementation's fully-qualified class name in the pipeline toon:
 * <pre>
 * processing:
 *   ingester: com.acme.MyEventIngester
 *   segments:
 *     CALL: config/source/call_schema.toon
 *     SMS:  config/source/sms_schema.toon
 * </pre>
 * The class must have a public no-arg constructor and be on the fat-JAR classpath.
 */
public interface FileIngester {

    /**
     * Parse {@code file} and populate one DuckDB table per event type in {@code conn}.
     *
     * @param file   the input file to parse
     * @param conn   the batch's DuckDB connection (ingester writes tables into this connection)
     * @param srcId  0-based member index within the batch; embed in every table name as
     *               {@code raw_<KEY>_f<srcId>} so the framework can union across members
     * @param cfg    pipeline configuration (may be used for date formats, delimiter, etc.)
     * @return one {@link Segment} per event-type table created; must not be null or empty
     * @throws java.io.IOException if the file cannot be read
     */
    List<Segment> ingest(File file, Connection conn, int srcId, PipelineConfig cfg) throws Exception;

    /**
     * Describes one event-type table the ingester created.
     *
     * @param key       segment key matching a key in {@code cfg.segmentSchemas},
     *                  e.g. {@code "CALL"}
     * @param rawTable  exact DuckDB table name created, e.g. {@code "raw_CALL_f0"}
     * @param stats     row counts for this table ({@link IngestResult#parsedRows()} etc.)
     */
    record Segment(String key, String rawTable, IngestResult stats) {}
}
