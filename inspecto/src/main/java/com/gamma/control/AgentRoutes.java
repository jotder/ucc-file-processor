package com.gamma.control;

import com.gamma.intelligence.AgentAskRequest;
import com.gamma.intelligence.AgentSessionRequest;
import com.gamma.intelligence.spi.IntelligenceAgent;

import java.util.Map;

/**
 * Embedded-intelligence routes ({@code /agent*}, AGT-5 P0): open a session, then ask it questions.
 * The agent lives in the optional {@code file-processor-intelligence} module — the core holds only
 * this seam — so every route degrades to 503 when it is absent. Sibling to {@link AssistRoutes}
 * (the reflex layer): that module answers one skill call per request, this one hosts multi-turn
 * sessions on the deliberative loop.
 */
final class AgentRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.post("/agent/sessions", (e, m) -> {
            Map<String, Object> body = api.body(e);
            String role = ApiContext.str(body, "role");
            Object page = body.get("page");
            return agentOr503(api).openSession(new AgentSessionRequest(role, mapField(page)));
        });
        api.post("/agent/sessions/(.+)/ask", (e, m) -> {
            Map<String, Object> body = api.body(e);
            String question = ApiContext.str(body, "question");
            if (question == null) throw new ApiException(400, "question is required");
            Object page = body.get("page");
            try {
                return agentOr503(api).ask(ApiContext.name(m), new AgentAskRequest(question, mapField(page)));
            } catch (IllegalArgumentException ex) {
                throw new ApiException(404, ex.getMessage());
            }
        });
    }

    /** The in-process intelligence agent, or 503 when the optional module is absent. */
    private IntelligenceAgent agentOr503(ApiContext api) {
        return api.service().intelligenceAgent().orElseThrow(() -> new ApiException(503,
                "intelligence agent not available (file-processor-intelligence not on classpath)"));
    }

    /** A nested JSON object from a request body as a {@code Map}, or an empty map when absent/not an object. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Object v) {
        return (v instanceof Map<?, ?> map) ? (Map<String, Object>) map : Map.of();
    }
}
