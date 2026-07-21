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
            String goalKind = ApiContext.str(body, "goalKind");
            try {
                return agentOr503(api).openSession(new AgentSessionRequest(role, mapField(page), goalKind));
            } catch (IllegalArgumentException ex) {   // unknown goalKind → reject at the edge
                throw new ApiException(400, ex.getMessage());
            }
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
        api.get("/agent/cases", (e, m) ->
                Map.of("cases", agentOr503(api).recentCases(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50))));
        api.get("/agent/cases/(.+)", (e, m) ->
                agentOr503(api).caseById(ApiContext.name(m))
                        .orElseThrow(() -> new ApiException(404, "unknown case: '" + ApiContext.name(m) + "'")));

        // AGT-5 P3 (autonomy L2): the approvals inbox. A mutating agent tool call parks in the
        // intelligence module's ApprovalStore until an operator decides here; the decision POST resumes
        // (approve) or denies the gated tool. Reads degrade to empty when the module is absent (no 503),
        // mirroring /agent/cases. The decision is itself audited by ControlApi.dispatch; a secured
        // edition prepends the approver-capability gate at the ApiContext/WriteGates seam (plan §6, L2).
        api.get("/agent/approvals", (e, m) ->
                Map.of("approvals", agentOr503(api).recentApprovals(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50))));
        api.get("/agent/approvals/(.+)", (e, m) ->
                agentOr503(api).approvalById(ApiContext.name(m))
                        .orElseThrow(() -> new ApiException(404, "unknown approval: '" + ApiContext.name(m) + "'")));
        api.post("/agent/approvals/(.+)/decision", (e, m) -> {
            Map<String, Object> body = api.body(e);
            Boolean approve = parseDecision(ApiContext.str(body, "decision"));
            if (approve == null) throw new ApiException(400, "decision is required and must be 'approve' or 'decline'");
            String decidedBy = ApiContext.str(body, "decidedBy");
            return agentOr503(api).decideApproval(ApiContext.name(m), approve,
                            decidedBy == null || decidedBy.isBlank() ? "operator" : decidedBy.trim())
                    .orElseThrow(() -> new ApiException(404,
                            "unknown or already-decided approval: '" + ApiContext.name(m) + "'"));
        });

        // AGT-5 P4 (autonomy L3): the bounded-autonomy policy — kill switch + per-action-class
        // mode/budget that gate the ops_monitor loop. Absent when there is no autonomy tier (→ 503, a
        // genuine "feature not present"). Writes are audited by ControlApi.dispatch; a secured edition
        // prepends the agent.admin capability gate at the ApiContext/WriteGates seam (plan §1, §6, L3).
        api.get("/agent/policy", (e, m) -> agentOr503(api).autonomyPolicy()
                .orElseThrow(() -> new ApiException(503, "autonomy policy not available (no L3 tier)")));
        api.put("/agent/policy", (e, m) -> {
            String by = actorOrOperator(e);
            return agentOr503(api).updateAutonomyPolicy(api.body(e), by)
                    .orElseThrow(() -> new ApiException(503, "autonomy policy not available (no L3 tier)"));
        });
        api.post("/agent/policy/kill-switch", (e, m) -> {
            Map<String, Object> body = api.body(e);
            Boolean engaged = parseEngaged(body.get("engaged"));
            if (engaged == null) throw new ApiException(400, "engaged is required and must be a boolean");
            return agentOr503(api).setAutonomyKillSwitch(engaged, actorOrOperator(e))
                    .orElseThrow(() -> new ApiException(503, "autonomy policy not available (no L3 tier)"));
        });
    }

    /** The request's audited actor (agent/human) as the policy's {@code updatedBy}, defaulting to "operator". */
    private static String actorOrOperator(HttpExchange e) {
        String actor = ApiContext.actor(e);
        return actor == null || actor.isBlank() ? "operator" : actor;
    }

    /** Parse a JSON boolean-ish {@code engaged} flag; {@code null} when unrecognized (→ 400). */
    private static Boolean parseEngaged(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return null;
        return switch (String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "on", "engage", "engaged", "1" -> Boolean.TRUE;
            case "false", "no", "off", "disengage", "disengaged", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Parse the decision field → approve (true) / decline (false), or {@code null} when unrecognized (→ 400). */
    private static Boolean parseDecision(String decision) {
        if (decision == null) return null;
        return switch (decision.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "approve", "approved", "yes", "true" -> Boolean.TRUE;
            case "decline", "declined", "deny", "denied", "reject", "rejected", "no", "false" -> Boolean.FALSE;
            default -> null;
        };
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
