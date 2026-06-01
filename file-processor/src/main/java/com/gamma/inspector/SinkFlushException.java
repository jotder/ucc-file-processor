package com.gamma.inspector;

/**
 * Signals a framework-side failure while flushing a streaming {@link DuckDbRecordSink} generation
 * (table create, batched insert, transform, partitioned write, or lineage). Distinct from a
 * file-decode failure thrown by the {@link com.gamma.etl.StreamingFileIngester}: a decode failure
 * means the input is bad (→ quarantine), whereas this means the pipeline/schema is at fault, so
 * {@link StreamingPluginBatchStrategy} lets it propagate and fails the batch rather than blaming
 * (and quarantining) the input file.
 */
final class SinkFlushException extends RuntimeException {
    SinkFlushException(String message, Throwable cause) { super(message, cause); }
}
