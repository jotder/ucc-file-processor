package com.gamma.etl;

import com.gamma.api.PublicApi;

/**
 * Counts returned from a single-file raw-ingestion pass into DuckDB.
 *
 * @param parsedRows        rows successfully appended to {@code raw_input}
 * @param errorRows         rows rejected in the appender loop (insufficient columns)
 * @param junkCandidateRows non-blank lines evaluated during junk detection that did
 *                          not qualify as data rows; used to detect wrong-schema files
 *                          where every row fails the column-count check before the
 *                          appender is even reached
 */
@PublicApi(since = "1.0.0")
public record IngestResult(long parsedRows, long errorRows, long junkCandidateRows) {}
