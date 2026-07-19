package com.gamma.intelligence;

import java.util.Map;

/**
 * A request to open an embedded-intelligence session (AGT-5, P0) — the wire shape of
 * {@code POST /agent/sessions}. {@code role} is the caller's product role (mapped onto the
 * eoiagent platform {@code Role} inside the optional {@code file-processor-intelligence} module);
 * {@code page} is the current UI page context (id + entity ids + filters), or empty when the
 * caller has none. {@code goalKind} (AGT-5 P1 slice B) optionally pins the session's goal kind
 * (e.g. {@code INVESTIGATION}); {@code null}/blank leaves the eoiagent default ({@code QA}). The
 * value is validated against the known kinds inside the intelligence module, which knows the enum.
 */
public record AgentSessionRequest(String role, Map<String, Object> page, String goalKind) {

    public AgentSessionRequest {
        page = page == null ? Map.of() : Map.copyOf(page);
    }

    /** Back-compat convenience — a session with no pinned goal kind (the eoiagent {@code QA} default). */
    public AgentSessionRequest(String role, Map<String, Object> page) {
        this(role, page, null);
    }
}
