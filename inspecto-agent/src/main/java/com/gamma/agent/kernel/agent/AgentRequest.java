package com.gamma.agent.kernel.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The neutral input to a {@link Capability}: the {@code capabilityId} (the intent), the
 * {@code screenContext} the calling surface supplies, any {@code partialInput} already entered, and
 * the free-text {@code userText}. The two maps are defensively copied and never {@code null} (null
 * <em>values</em> inside are tolerated, so a plain map copy is used rather than {@code Map.copyOf}).
 */
public record AgentRequest(String capabilityId, Map<String, Object> screenContext,
                           Map<String, Object> partialInput, String userText) {

    public AgentRequest {
        screenContext = (screenContext == null) ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(screenContext));
        partialInput = (partialInput == null) ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(partialInput));
    }

    /** A string field from {@link #screenContext}, or {@code null} when absent/blank. */
    public String context(String key) {
        Object v = screenContext.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
    }
}
