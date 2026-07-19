package com.gamma.agent.kernel.observe;

import com.gamma.event.EventLog;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An {@link AuditSink} that bridges the agent-kernel's {@link AgentEvent} island onto the canonical
 * ledger: each variant is mapped to a dotted, domain-canonical {@link Signal} and emitted via
 * {@link EventLog#current()} (event-signal-backbone-plan §4.2/S1). This is what turns agent run/tool
 * telemetry — today discarded to logs by {@link com.gamma.agent.LoggingAuditSink} — into real
 * {@code agent.*} facts on the one ledger.
 *
 * <p>{@code correlationId} is the capability invocation id ({@link AgentEvent#capabilityId()} — the
 * only identifier common to every variant); {@code source} is {@code Ref(kind="agent-capability",
 * id=capabilityId)}. Payloads carry keys/summaries only, mirroring each record's own ADR-0008
 * discipline — no data-plane values are introduced here that the event didn't already carry.
 */
public final class EventLogAuditSink implements AuditSink {

    @Override
    public void emit(AgentEvent event) {
        if (event == null) return;
        Signal signal = toSignal(event);
        if (signal == null) return;
        try {
            EventLog.current().emit(signal.toEvent());
        } catch (RuntimeException ignored) {
            // an observability sink must never break the thing it observes
        }
    }

    private static Signal toSignal(AgentEvent event) {
        String capabilityId = event.capabilityId();
        Ref source = Ref.of("agent-capability", capabilityId);
        Instant at = Instant.ofEpochMilli(event.epochMillis());

        return switch (event) {
            case AgentStarted e -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("contextKeys", e.contextKeys());
                yield signal("agent.run.started", Severity.INFO, source, capabilityId, at, payload);
            }
            case AgentCompleted e -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", e.status());
                payload.put("evidenceCount", e.evidenceCount());
                payload.put("durationMs", e.durationMs());
                payload.put("contextKeys", e.contextKeys());
                payload.put("servedBy", e.servedBy());
                payload.put("modelInvoked", e.modelInvoked());
                payload.put("repairRounds", e.repairRounds());
                payload.put("confidence", e.confidence());
                payload.put("promptTokens", e.promptTokens());
                payload.put("completionTokens", e.completionTokens());
                yield signal("agent.run.completed", Severity.INFO, source, capabilityId, at, payload);
            }
            case AgentFailed e -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("category", e.category());
                payload.put("reason", e.reason());
                yield signal("agent.run.failed", Severity.ERROR, source, capabilityId, at, payload);
            }
            case ModelCalled e -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tier", e.tier());
                payload.put("jsonFormat", e.jsonFormat());
                yield signal("agent.model.called", Severity.INFO, source, capabilityId, at, payload);
            }
            case ToolCalled e -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolId", e.toolId());
                yield signal("agent.tool.called", Severity.INFO, source, capabilityId, at, payload);
            }
            case ToolCompleted e -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolId", e.toolId());
                payload.put("evidenceCount", e.evidenceCount());
                payload.put("durationMs", e.durationMs());
                yield signal("agent.tool.completed", Severity.INFO, source, capabilityId, at, payload);
            }
            case HumanDecided e -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("decision", e.decision());
                payload.put("reviewer", e.reviewer());
                payload.put("reference", e.reference());
                yield signal("agent.human.decided", Severity.INFO, source, capabilityId, at, payload,
                        Ref.of("human", e.reviewer()));
            }
        };
    }

    private static Signal signal(String type, Severity severity, Ref source, String correlationId,
                                  Instant at, Map<String, Object> payload) {
        return signal(type, severity, source, correlationId, at, payload, null);
    }

    private static Signal signal(String type, Severity severity, Ref source, String correlationId,
                                  Instant at, Map<String, Object> payload, Ref actor) {
        return new Signal(null, type, at, severity, source, null, correlationId, null, null, actor,
                type, payload, 1);
    }
}
