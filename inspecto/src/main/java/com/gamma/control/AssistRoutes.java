package com.gamma.control;

import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.spi.AssistAgent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assist agent routes ({@code /assist*}): recent failure diagnoses (v3.7.0), the model-provider
 * settings read/write/test surface (v4.1), and the {@code POST /assist/{intent}} skill dispatch
 * (v3.3.0). The agent lives in the optional {@code file-processor-agent} module — the core holds
 * only this seam — so every route degrades to 503 (or an empty list) when it is absent.
 *
 * <p>Registration order is significant: the {@code /assist/settings*} and {@code /assist/metrics}
 * routes register <b>before</b> the {@code /assist/(.+)} intent catch-all so "settings"/"metrics"
 * never resolve as skill intents. This module is registered last so the catch-all stays the
 * last-matched route overall. Extracted verbatim from {@link ControlApi}.
 */
final class AssistRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        // ── v3.7.0: recent failure diagnoses (read-only) — registered before the POST catch-all ──
        api.get("/assist/diagnoses", (e, m) ->
                api.service().assistAgent()
                        .map(a -> (Object) a.recentDiagnoses(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50)))
                        .orElse(List.of()));
        // ── v4.1: assist model-provider settings (masked read / validated write / round-trip test).
        // Registered BEFORE the intent catch-all so "settings" never resolves as a skill intent. ──
        api.get("/assist/settings", (e, m) -> assistAgentOr503(api).settings());
        api.get("/assist/metrics", (e, m) -> assistAgentOr503(api).metrics());
        api.post("/assist/settings/test", (e, m) -> assistAgentOr503(api).testSettings());
        api.post("/assist/settings", (e, m) -> {
            try {
                return assistAgentOr503(api).updateSettings(api.body(e));
            } catch (IllegalArgumentException ex) {
                throw new ApiException(400, ex.getMessage());
            }
        });
        api.post("/assist/(.+)", (e, m) -> assist(api, ApiContext.name(m), api.body(e)));
    }

    /** The in-process assist agent, or 503 when the optional module is absent (v4.1 settings routes). */
    private AssistAgent assistAgentOr503(ApiContext api) {
        return api.service().assistAgent().orElseThrow(() -> new ApiException(503,
                "assist agent not available (file-processor-agent not on classpath)"));
    }

    /**
     * Dispatch one assist request to the in-process {@link AssistAgent} (v3.3.0). The {@code intent}
     * (path segment) selects the skill; the JSON body supplies {@code screenContext},
     * {@code partialInput}, and {@code userText}. The agent lives in the optional
     * {@code file-processor-agent} module — core holds only this seam — so the agent may be absent.
     *
     * <p>Status mapping (fail-safe, never throws to the model): no agent on the classpath → 503;
     * an unknown intent ({@link AssistResult.Status#UNSUPPORTED}) → 404; a skill whose model is
     * unavailable ({@link AssistResult.Status#UNAVAILABLE}) → 503 with its message; otherwise the
     * {@link AssistResult} is returned as JSON (200).
     */
    private Object assist(ApiContext api, String intent, Map<String, Object> body) {
        Optional<AssistAgent> agent = api.service().assistAgent();
        if (agent.isEmpty())
            throw new ApiException(503, "assist agent not available (file-processor-agent not on classpath)");
        AssistRequest req = new AssistRequest(
                intent, mapField(body, "screenContext"), mapField(body, "partialInput"), ApiContext.str(body, "userText"));
        AssistResult result = agent.get().assist(req);
        return switch (result.status()) {
            case UNSUPPORTED -> throw new ApiException(404, "unknown assist intent: " + intent);
            case UNAVAILABLE -> throw new ApiException(503,
                    result.message() == null ? "assist model unavailable" : result.message());
            case OK -> result;
        };
    }

    /** A nested JSON object from a request body as a {@code Map}, or an empty map when absent/not an object. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return (v instanceof Map<?, ?> map) ? (Map<String, Object>) map : Map.of();
    }
}
