package com.gamma.assist;

import com.gamma.api.PublicApi;

import java.util.List;

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
        String message) {

    /** Outcome discriminator; drives the HTTP status of the {@code /assist} route. */
    public enum Status { OK, UNSUPPORTED, UNAVAILABLE }

    /** A grounding source backing a claim in {@link #answer} (a catalog node id, report, or doc anchor). */
    public record Citation(String source, String ref) {}

    public AssistResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** No skill is registered for this intent (or no agent is present): HTTP 404. */
    public static AssistResult unsupported(String intent) {
        return new AssistResult(intent, Status.UNSUPPORTED, null, List.of(), List.of(),
                null, null, false, null,
                "no assist skill is registered for intent '" + intent + "'");
    }

    /** The skill exists but its model/provider is unavailable (e.g. no Ollama): HTTP 503. */
    public static AssistResult unavailable(String intent, String message) {
        return new AssistResult(intent, Status.UNAVAILABLE, null, List.of(), List.of(),
                null, null, false, null, message);
    }

    /** A read-only, validated answer with grounding citations and follow-up links (explain-entity). */
    public static AssistResult answer(String intent, String answer,
                                      List<Citation> citations, List<String> links) {
        return new AssistResult(intent, Status.OK, answer, citations, links,
                null, "local", true, null, null);
    }
}
