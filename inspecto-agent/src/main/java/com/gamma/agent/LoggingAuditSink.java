package com.gamma.agent;

import com.gamma.agentkernel.observe.AgentCompleted;
import com.gamma.agentkernel.observe.AgentEvent;
import com.gamma.agentkernel.observe.AuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;

/**
 * An {@link AuditSink} decorator that logs a one-line, keys-only operator summary for each
 * {@link AgentCompleted} (ADR-0008) and then forwards the event to the wrapped sink.
 *
 * <p>It exists so the familiar {@code [ASSIST] intent=… status=…} log survives the U1→R1 move of audit
 * emission out of {@code UccAssistAgent} and into the shared {@code SyncOrchestrator}: the agent wraps
 * the injected sink with this decorator before handing it to the {@code AgentContext}, so the
 * orchestrator's {@code ctx.audit().emit(...)} both logs and delegates to whatever sink the embedder
 * supplied (e.g. a capturing list in tests). The event content is unchanged — logging is purely
 * additive over the wrapped sink.
 */
public final class LoggingAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuditSink.class);

    private final AuditSink delegate;

    public LoggingAuditSink(AuditSink delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void emit(AgentEvent event) {
        if (event instanceof AgentCompleted e) {
            log.info("[ASSIST] intent={} status={} evidence={} ms={} confidence={} repaired={} tier={} ctxKeys={}",
                    e.capabilityId(), e.status(), e.evidenceCount(), e.durationMs(),
                    String.format(Locale.ROOT, "%.2f", e.confidence()), e.repairRounds() > 0,
                    e.servedBy(), e.contextKeys());
        }
        delegate.emit(event);
    }
}
