package com.gamma.assist;

import com.gamma.api.PublicApi;

import java.util.List;

/**
 * A diagnosis of a {@code FAILED} batch produced by the optional assist agent's failure-diagnosis
 * reactor (v3.7.0, M7) — the wire shape of one entry in the {@code GET /assist/diagnoses} response.
 * A pure, JSON-serializable record in the lean core; the AI that produces it (and the in-memory
 * store it is held in) lives in the optional {@code file-processor-agent} module and is reached
 * through the {@link com.gamma.assist.spi.AssistAgent#recentDiagnoses(int)} SPI seam — core never
 * depends on the agent.
 *
 * <p>The reactor always computes a {@link #severity} and a {@link #rootCause} from a deterministic
 * heuristic over the batch's error detail, so a diagnosis exists even with no model configured; when
 * {@link #heuristicOnly} is {@code true} no language model contributed (CPU-only / air-gapped /
 * abstaining). When a model is available it enriches the {@link #rootCause} prose and may attach a
 * {@link #suggestedAlertRuleToon} draft. Diagnoses carry operational metadata only — never row content.
 *
 * @param batchId                the failed batch's id
 * @param pipeline               the pipeline that produced it
 * @param severity               triage severity
 * @param rootCause              a plain-language root-cause explanation (heuristic, or model-enriched)
 * @param suggestedAlertRuleToon a draft alert-rule {@code .toon} the operator may save, or {@code null}
 * @param heuristicOnly          {@code true} when no model contributed (deterministic heuristic only)
 * @param epochMillis            when the diagnosis was produced (wall-clock, for ordering/display)
 * @param citations              grounding sources (e.g. the pipeline's catalog SOURCE node id)
 * @since 3.7.0
 */
@PublicApi(since = "3.7.0")
public record Diagnosis(
        String batchId,
        String pipeline,
        Severity severity,
        String rootCause,
        String suggestedAlertRuleToon,
        boolean heuristicOnly,
        long epochMillis,
        List<AssistResult.Citation> citations) {

    /** Triage severity for a failed batch. */
    public enum Severity { INFO, WARNING, CRITICAL }

    public Diagnosis {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
