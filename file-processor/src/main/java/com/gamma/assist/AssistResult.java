package com.gamma.assist;

import com.gamma.api.PublicApi;

import java.util.List;
import java.util.Map;

/**
 * The output of an assist skill (v3.3.0) — the wire shape of the {@code POST /assist/{intent}}
 * response. A pure, JSON-serializable record in the lean core; the AI that produces it lives in
 * the optional {@code file-processor-agent} module.
 *
 * <p>{@link #status} is the discriminator the control plane uses to choose an HTTP status:
 * <ul>
 *   <li>{@link Status#OK} — a real answer (HTTP 200);</li>
 *   <li>{@link Status#UNSUPPORTED} — no skill is registered for the intent (HTTP 404);</li>
 *   <li>{@link Status#UNAVAILABLE} — the skill exists but its model/provider is unavailable,
 *       e.g. no Ollama endpoint configured (HTTP 503).</li>
 * </ul>
 *
 * <p>For the read-only {@code explain-entity} slice, {@link #applyVia} is always {@code null}:
 * the agent proposes, it never carries a write credential. Write-bearing skills (M4+) set it to
 * the control-plane endpoint that applies the suggestion with the human's credential.
 *
 * <p>{@link #data} (since 3.4.0) carries a skill-specific structured payload alongside the prose
 * {@link #answer} — e.g. {@code nl-to-schedule} returns {@code {cron, onPipeline, jobType,
 * humanReadable, nextRuns[], draftToon, findings[]}} so a UI can render the schedule and offer the
 * draft {@code .toon} to save. It stays JSON-serializable (JDK + Jackson only) and is never null
 * (an empty map for skills that have no structured output, like {@code explain-entity}).
 *
 * @since 3.3.0
 */
@PublicApi(since = "3.3.0")
public record AssistResult(
        String intent,
        Status status,
        String answer,
        List<Citation> citations,
        List<String> links,
        String rationale,
        String confidence,
        boolean validated,
        String applyVia,
        String message,
        Map<String, Object> data) {

    /** Outcome discriminator; drives the HTTP status of the {@code /assist} route. */
    public enum Status { OK, UNSUPPORTED, UNAVAILABLE }

    /** A grounding source backing a claim in {@link #answer} (a catalog node id, report, or doc anchor). */
    public record Citation(String source, String ref) {}

    public AssistResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        links = links == null ? List.of() : List.copyOf(links);
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** No skill is registered for this intent (or no agent is present): HTTP 404. */
    public static AssistResult unsupported(String intent) {
        return new AssistResult(intent, Status.UNSUPPORTED, null, List.of(), List.of(),
                null, null, false, null,
                "no assist skill is registered for intent '" + intent + "'", Map.of());
    }

    /** The skill exists but its model/provider is unavailable (e.g. no Ollama): HTTP 503. */
    public static AssistResult unavailable(String intent, String message) {
        return new AssistResult(intent, Status.UNAVAILABLE, null, List.of(), List.of(),
                null, null, false, null, message, Map.of());
    }

    /** A read-only, validated answer with grounding citations and follow-up links (explain-entity). */
    public static AssistResult answer(String intent, String answer,
                                      List<Citation> citations, List<String> links) {
        return new AssistResult(intent, Status.OK, answer, citations, links,
                null, "local", true, null, null, Map.of());
    }

    /**
     * A validated <em>draft</em> result (since 3.4.0) — a write-adjacent skill's structured
     * suggestion the user can review and save. {@code applyVia} stays {@code null} because the
     * milestone is draft-only (no create/update-config endpoint yet; V-9): the {@code data} payload
     * carries the draft artifact (e.g. {@code draftToon}) the user persists by hand.
     */
    public static AssistResult draft(String intent, String answer,
                                     List<Citation> citations, List<String> links,
                                     Map<String, Object> data) {
        return new AssistResult(intent, Status.OK, answer, citations, links,
                null, "local", true, null, null, data);
    }
}
