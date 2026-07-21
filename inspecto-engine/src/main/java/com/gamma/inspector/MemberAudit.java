package com.gamma.inspector;

import com.gamma.etl.Batch;

import java.time.LocalDateTime;

/**
 * Per-input-file audit record accumulated while a {@link BatchIngestStrategy} processes
 * a batch, then consumed by {@link BatchProcessor}'s audit assembly. Extracted from
 * {@code BatchProcessor} so both the CSV and plugin strategies can build it directly.
 */
record MemberAudit(int srcId, String filename, String status,
                   long parsedRows, long errorRows, String error, LocalDateTime start) {

    static MemberAudit accepted(Batch.Member m, long parsed, long errors, LocalDateTime start) {
        return new MemberAudit(m.srcId(), m.file().getName(), "SUCCESS", parsed, errors, "", start);
    }

    static MemberAudit rejected(Batch.Member m, String status, String error, LocalDateTime start) {
        return new MemberAudit(m.srcId(), m.file().getName(), status, 0, 0, error, start);
    }
}
