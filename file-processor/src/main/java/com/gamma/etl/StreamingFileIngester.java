package com.gamma.etl;

import com.gamma.api.PublicApi;

import java.io.File;

/**
 * Streaming alternative to {@link FileIngester} for custom file formats (binary, proprietary text,
 * ASN.1, …) that are too large to materialise whole. Instead of building complete DuckDB tables for
 * the entire file and returning them, the implementation decodes records and pushes them one at a
 * time into the supplied {@link RecordSink}; the framework owns buffering, the DuckDB writes,
 * transform, partitioned output, and lineage — and flushes bounded "generations" so peak scratch
 * stays bounded regardless of total file size.
 *
 * <h3>When to implement this instead of {@link FileIngester}</h3>
 * <ul>
 *   <li>The input is a single very large file (hundreds of GB / TB) whose decoded rows would not fit
 *       in heap or scratch if fully materialised.</li>
 *   <li>The format is not line-delimited, so the built-in CSV auto-chunker
 *       ({@code processing.chunking}) cannot split it — only your decoder knows where records end.</li>
 * </ul>
 * For modest files, {@link FileIngester} remains perfectly fine and is simpler. Both are discovered
 * the same way — by fully-qualified class name in {@code processing.ingester}. When a class
 * implements <em>both</em> interfaces, the streaming path takes precedence.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Decode {@code file} and call {@link RecordSink#emit} once per parsed record, tagging each
 *       with its segment key. Optionally {@link RecordSink#define} a segment's columns first (see
 *       {@link RecordSink} for when this is required). Do not populate DuckDB tables yourself and do
 *       not add a {@code __src_id} column — the framework owns table creation and lineage tagging.</li>
 *   <li>Throw {@link java.io.IOException} (or any exception) if the file cannot be read/decoded —
 *       the framework quarantines it as {@code QUARANTINED_UNREADABLE}. If you emit zero records
 *       across all segments, the framework quarantines as {@code QUARANTINED_MISMATCH}.</li>
 *   <li>You need not (and should not) flush, transform, or write output — returning normally signals
 *       end-of-file and the framework performs the final flush.</li>
 * </ul>
 *
 * <h3>Registration</h3>
 * Identical to {@link FileIngester}: reference the FQCN in the pipeline toon and declare segments.
 * The class must have a public no-arg constructor and be on the fat-JAR classpath.
 * <pre>
 * processing:
 *   ingester: com.acme.AsnCdrIngester   # implements StreamingFileIngester
 *   segments:
 *     CALL: config/source/call_schema.toon
 *     SMS:  config/source/sms_schema.toon
 * </pre>
 *
 * @see RecordSink
 * @see FileIngester
 */
@PublicApi(since = "3.10.0")
public interface StreamingFileIngester {

    /**
     * Decode {@code file}, emitting each parsed record into {@code sink}.
     *
     * @param file  the input file to decode
     * @param sink  framework-owned record sink (valid only for the duration of this call)
     * @param srcId 0-based member index within the batch (the framework tags lineage with it)
     * @param cfg   pipeline configuration (date formats, delimiter, {@code ingester_config}, …)
     * @throws java.io.IOException if the file cannot be read or decoded
     */
    void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception;
}
