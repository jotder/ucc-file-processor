package com.gamma.intelligence;

import java.util.Map;

/**
 * A request to open an embedded-intelligence session (AGT-5, P0) — the wire shape of
 * {@code POST /agent/sessions}. {@code role} is the caller's product role (mapped onto the
 * eoiagent platform {@code Role} inside the optional {@code file-processor-intelligence} module);
 * {@code page} is the current UI page context (id + entity ids + filters), or empty when the
 * caller has none.
 */
public record AgentSessionRequest(String role, Map<String, Object> page) {

    public AgentSessionRequest {
        page = page == null ? Map.of() : Map.copyOf(page);
    }
}
