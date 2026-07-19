package com.gamma.control;

import com.gamma.intelligence.AgentAnswerSink;
import com.gamma.intelligence.AgentAskRequest;
import com.gamma.intelligence.AgentAskResult;
import com.gamma.intelligence.AgentSessionRequest;
import com.gamma.intelligence.spi.IntelligenceAgent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
        api.post("/agent/sessions/(.+)/ask/stream", (e, m) -> {
            Map<String, Object> body = api.body(e);
            String question = ApiContext.str(body, "question");
            if (question == null) throw new ApiException(400, "question is required");
            Object page = body.get("page");
            streamAsk(agentOr503(api), ApiContext.name(m), new AgentAskRequest(question, mapField(page)), e);
            return ApiContext.HANDLED;
        });
    }

    /**
     * Server-Sent Events variant of {@code ask}: one {@code data:} event per token, an optional
     * {@code event: artifact} frame carrying an A2UI artifact JSON (before completion, when the
     * answer produced one), then a terminal {@code event: complete} carrying the
     * {@link AgentAskResult} JSON, or {@code event: error}. An unknown session surfaces as an
     * {@code error} SSE frame (never a 404) — the response's headers, including the status, are
     * already committed by the time streaming starts.
     */
    private void streamAsk(IntelligenceAgent agent, String sessionId, AgentAskRequest request, HttpExchange e) {
        try {
            e.getResponseHeaders().set("Content-Type", "text/event-stream");
            e.getResponseHeaders().set("Cache-Control", "no-cache");
            e.sendResponseHeaders(200, 0); // 0 => chunked transfer, unknown total length
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        OutputStream out = e.getResponseBody();
        AgentAnswerSink sink = new AgentAnswerSink() {
            @Override public void onToken(String token) { writeSse(out, null, token); }
            @Override public void onArtifact(Map<String, Object> artifact) {
                try {
                    writeSse(out, "artifact", ApiContext.JSON.writeValueAsString(artifact));
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    writeSse(out, "error", "failed to serialize the artifact: " + ex.getMessage());
                }
            }
            @Override public void onComplete(AgentAskResult result) {
                try {
                    writeSse(out, "complete", ApiContext.JSON.writeValueAsString(result));
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    writeSse(out, "error", "failed to serialize the answer: " + ex.getMessage());
                }
            }
            @Override public void onError(String message) { writeSse(out, "error", message); }
        };
        try {
            agent.askStream(sessionId, request, sink);
        } catch (UncheckedIOException ex) {
            // Client disconnected mid-stream; headers are already sent so there's nothing left to
            // respond with — swallow rather than let ControlApi.dispatch try to write a second response.
        }
    }

    /** Writes one SSE frame (multi-line {@code data} split across {@code data:} lines) and flushes. */
    private static void writeSse(OutputStream out, String event, String data) {
        try {
            StringBuilder frame = new StringBuilder();
            if (event != null) frame.append("event: ").append(event).append('\n');
            for (String line : data.split("\n", -1)) frame.append("data: ").append(line).append('\n');
            frame.append('\n');
            out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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
