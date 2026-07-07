package com.gamma.intelligence;

import java.util.Map;

/** One question sent to an open session — the wire shape of {@code POST /agent/sessions/{id}/ask}. */
public record AgentAskRequest(String question, Map<String, Object> page) {

    public AgentAskRequest {
        page = page == null ? Map.of() : Map.copyOf(page);
    }
}
