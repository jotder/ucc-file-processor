package com.gamma.agent.diagnose;

import com.gamma.assist.AssistResult;
import com.gamma.assist.Diagnosis;
import com.gamma.etl.BatchEvent;

import java.util.List;

/**
 * The deterministic, model-free first pass over a failed {@link BatchEvent} (v3.7.0, M7). It maps the
 * event's error detail to a {@link Diagnosis.Severity} and a rule-of-thumb root cause using only the
 * fields on the event — no language model, no network.
 *
 * <p>This is the always-on floor of the failure reactor: a diagnosis exists even on a CPU-only /
 * air-gapped / assist-disabled deployment (abstain-by-default — see {@link FailureReactor}). When a
 * model <em>is</em> available the reactor enriches the {@link Diagnosis#rootCause()} prose on top of
 * this baseline, but the severity classification stays deterministic. Pure and side-effect-free, so
 * it is trivially unit-testable.
 */
public final class HeuristicDiagnoser {

    private HeuristicDiagnoser() {}

    /**
     * Diagnose a batch event with no model. Severity reflects how much got through; the root cause is
     * inferred from the batch's error text (and names the offending file when known).
     *
     * @param e           the (typically FAILED) batch event, carrying error detail since v3.7.0
     * @param epochMillis the timestamp to stamp on the diagnosis (injected for deterministic tests)
     * @param citations   grounding sources to attach (e.g. the pipeline's catalog SOURCE node), may be empty
     */
    public static Diagnosis diagnose(BatchEvent e, long epochMillis, List<AssistResult.Citation> citations) {
        Diagnosis.Severity severity = severityOf(e);
        String rootCause = rootCauseOf(e);
        return new Diagnosis(e.batchId(), e.pipeline(), severity, rootCause,
                null, /* heuristicOnly */ true, epochMillis, citations);
    }

    /** Severity from how much of the batch survived: nothing out on a failure is critical. */
    public static Diagnosis.Severity severityOf(BatchEvent e) {
        boolean failed = "FAILED".equalsIgnoreCase(e.status());
        if (failed) {
            return e.outputRows() <= 0 ? Diagnosis.Severity.CRITICAL : Diagnosis.Severity.WARNING;
        }
        // A non-failed terminal batch that still had unparseable rows is a soft warning.
        return e.errorRows() > 0 || e.rejectedCount() > 0 ? Diagnosis.Severity.WARNING : Diagnosis.Severity.INFO;
    }

    /** A plain-language root cause inferred from the error text, naming the offending file when known. */
    public static String rootCauseOf(BatchEvent e) {
        String err = e.error() == null ? "" : e.error().toLowerCase();
        String base;
        if (err.isBlank()) {
            base = "Batch failed with no error detail recorded; inspect the pipeline run logs.";
        } else if (err.contains("selector") || err.contains("schema") || err.contains("mismatch")
                || err.contains("column")) {
            base = "Schema/selector mismatch: the input columns don't match the configured schema.";
        } else if (err.contains("parse") || err.contains("malformed") || err.contains("invalid")
                || err.contains("delimiter") || err.contains("encoding")) {
            base = "Parse error: one or more rows could not be parsed under the configured format.";
        } else if (err.contains("no such file") || err.contains("not found") || err.contains("missing")
                || err.contains("nosuchfile")) {
            base = "Missing input: an expected file or path was not found.";
        } else if (err.contains("memory") || err.contains("oom") || err.contains("heap")) {
            base = "Resource exhaustion: the batch ran out of memory.";
        } else if (err.contains("permission") || err.contains("denied") || err.contains("access")) {
            base = "Access denied: the process lacks permission for an input or output path.";
        } else {
            base = "Batch failed: " + truncate(e.error(), 200);
        }
        StringBuilder sb = new StringBuilder(base);
        if (e.offendingFile() != null && !e.offendingFile().isBlank())
            sb.append(" (offending file: ").append(e.offendingFile()).append(')');
        if (e.errorRows() > 0)
            sb.append(' ').append(e.errorRows()).append(" row(s) failed to parse.");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
