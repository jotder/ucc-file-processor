package com.gamma.agent.kernel.observe;

/**
 * Where {@link AgentEvent}s are emitted. The default is the in-memory {@link RingBufferAuditSink};
 * a durable sink (e.g. CVVE's Postgres ledger) is a ring-2 companion. Every sink must uphold the
 * keys/summaries-only invariant (ADR-0008).
 */
@FunctionalInterface
public interface AuditSink {

    /** Record one event. Must not throw on the hot path; implementations swallow their own errors. */
    void emit(AgentEvent event);

    /** A sink that discards everything. */
    AuditSink NONE = event -> { };
}
