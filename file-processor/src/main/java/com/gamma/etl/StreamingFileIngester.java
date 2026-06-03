package com.gamma.etl;

import com.gamma.api.PublicApi;

import java.io.File;

/**
 * The plugin ingestion SPI for custom file formats (binary, proprietary text, ASN.1, delimited, …).
 * The implementation decodes records and pushes them one at a time into the supplied
 * {@link RecordSink}; the framework owns buffering, the DuckDB writes, transform, partitioned output,
 * and lineage. The {@link com.gamma.inspector.StreamingPluginBatchStrategy unified engine} then picks
 * an execution mode per batch by file size:
 *
 * <ul>
 *   <li><b>Union mode</b> (many small files) — each member's records are accumulated and the batch is
 *       transformed/written once, amortising fixed per-batch cost and consolidating output.</li>
 *   <li><b>Generation mode</b> (a single very large file, ≥ {@code processing.streaming.large_file_bytes})
 *       — records flush in bounded "generations" so peak heap and scratch stay bounded regardless of
 *       total file size.</li>
 * </ul>
 *
 * <p>The same ingester serves both modes — you write one decoder and the framework chooses. For
 * line-delimited CSV the built-in CSV path ({@code processing.engine}/{@code processing.chunking})
 * remains available; this SPI is for everything else.
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
 * Reference the FQCN in the pipeline toon and declare segments. The class must have a public no-arg
 * constructor and be on the fat-JAR classpath.
 * <pre>
 * processing:
 *   ingester: com.acme.AsnCdrIngester   # implements StreamingFileIngester
 *   segments:
 *     CALL: config/source/call_schema.toon
 *     SMS:  config/source/sms_schema.toon
 * </pre>
 *
 * @see RecordSink
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
