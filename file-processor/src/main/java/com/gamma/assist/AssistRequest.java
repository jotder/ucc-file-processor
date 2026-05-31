package com.gamma.assist;

import com.gamma.api.PublicApi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The input to an assist skill (v3.3.0). Carries everything a skill needs to produce a
 * grounded, validated suggestion: the {@code intent} (the skill id, e.g. {@code "explain-entity"}),
 * the {@code screenContext} the calling screen can supply (e.g. {@code entityType}/{@code id}),
 * any {@code partialInput} the user has already entered (for draft-completing skills), and the
 * free-text {@code userText} question.
 *
 * <p>This is a pure, JSON-serializable record in the lean core — the wire shape of the
 * {@code POST /assist/{intent}} request body. The AI that consumes it lives entirely in the
 * optional {@code file-processor-agent} module; core never depends on a model.
 *
 * <p>The two maps are defensively copied and never {@code null} (an absent map becomes empty),
 * so skills can read them without null checks. {@code null} <em>values</em> inside the maps are
 * tolerated (JSON bodies may contain them), so the copy is a plain {@link LinkedHashMap} rather
 * than {@link Map#copyOf} (which rejects nulls).
 *
 * @since 3.3.0
 */
@PublicApi(since = "3.3.0")
public record AssistRequest(
        String intent,
        Map<String, Object> screenContext,
        Map<String, Object> partialInput,
        String userText) {

    public AssistRequest {
        screenContext = screenContext == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(screenContext));
        partialInput = partialInput == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(partialInput));
    }

    /** A string field from {@link #screenContext}, or {@code null} when absent/blank. */
    public String context(String key) {
        Object v = screenContext.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
    }
}
