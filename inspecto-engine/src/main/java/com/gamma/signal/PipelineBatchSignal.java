package com.gamma.signal;

import com.gamma.etl.BatchEvent;
import com.gamma.event.EventLog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits the canonical {@code pipeline.batch.committed|failed} Signal for a terminal batch onto the
 * current space's ledger. This is the observability tail formerly inlined in
 * {@code etl.BatchAuditWriter.emitBatchSignal}; it was lifted here — above {@code com.gamma.etl} — so
 * the ETL layer stays free of the {@code event}/{@code signal} packages and can be a foundation layer.
 * The composition root wires it via
 * {@code BatchAuditWriter.setTerminalBatchSink(PipelineBatchSignal::emit)}.
 *
 * <p>Uses {@link EventLog#current()} — the established ambient idiom for code with no injected
 * per-space handle (mirrors {@code ReportJob}'s {@code REPORT_READY} emission). It is additive to the
 * {@code BatchEventBus} fan-out and to {@code JobService.mirrorPipelineCommit}'s {@code pipeline.commit}
 * mirror (a different signal type); none of those are replaced.
 */
public final class PipelineBatchSignal {

    private PipelineBatchSignal() {
    }

    /** Build the canonical Signal from a terminal {@link BatchEvent} and emit it onto the current ledger. */
    public static void emit(BatchEvent event) {
        boolean success = "SUCCESS".equals(event.status());
        String type = success ? "pipeline.batch.committed" : "pipeline.batch.failed";

        // Event's payload immutability (Map.copyOf, Event.java) rejects null values, so only put the
        // optional error-detail fields when present — mirrors BatchEvent's own null-ability.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", event.status());
        payload.put("outputRows", event.outputRows());
        payload.put("durationMs", event.durationMs());
        payload.put("rejectedCount", event.rejectedCount());
        payload.put("partitions", event.partitions());
        if (event.error() != null && !event.error().isBlank()) payload.put("error", event.error());
        if (event.offendingFile() != null) payload.put("offendingFile", event.offendingFile());
        payload.put("errorRows", event.errorRows());

        Signal signal = new Signal(null, type, Instant.now(), success ? Severity.INFO : Severity.WARN,
                Ref.of("pipeline", event.pipeline()), Ref.of("pipeline", event.pipeline()),
                event.batchId(), null, null, null, type, payload, 1);
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // an observability sink must never break the batch commit it is announcing
        }
    }
}
