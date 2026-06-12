package com.gamma.agent;

import com.gamma.agentkernel.agent.AgentResult;
import com.gamma.agentkernel.observe.AgentCompleted;
import com.gamma.agentkernel.observe.AgentEvent;
import com.gamma.agentkernel.observe.AuditSink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory per-intent assist counters (v4.1, B2 observability) — an {@link AuditSink} decorator that
 * tallies every {@link AgentCompleted} flowing to the wrapped sink and exposes a keys-only snapshot
 * behind {@code GET /assist/metrics}. This is the operator's tuning surface: abstain/unavailable and
 * repair rates per skill are what calibrate the {@code assist.confidence.threshold} and
 * {@code assist.repair.rounds} knobs (B1) against real traffic.
 *
 * <p>Counts and aggregates only — no prompts, no answers, no data-plane values (ADR-0008). Process
 * lifetime; counters reset on restart like the diagnosis ring.
 */
public final class AssistMetrics implements AuditSink {

    private final AuditSink delegate;
    private final Map<String, IntentStats> byIntent = new ConcurrentHashMap<>();

    public AssistMetrics(AuditSink delegate) {
        this.delegate = (delegate == null) ? AuditSink.NONE : delegate;
    }

    @Override
    public void emit(AgentEvent event) {
        if (event instanceof AgentCompleted e) {
            IntentStats s = byIntent.computeIfAbsent(e.capabilityId(), k -> new IntentStats());
            s.calls.increment();
            if (e.status() == AgentResult.Status.OK) s.ok.increment();
            else if (e.status() == AgentResult.Status.UNAVAILABLE) s.unavailable.increment();
            else s.unsupported.increment();
            if (e.repairRounds() > 0) s.repaired.increment();
            s.totalMs.add(e.durationMs());
            s.lastConfidence = e.confidence();
            s.lastEpochMillis = e.epochMillis();
        }
        delegate.emit(event);
    }

    /** JSON-ready snapshot, intents sorted for stable output. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new TreeMap<>();
        byIntent.forEach((intent, s) -> {
            long calls = s.calls.sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", calls);
            m.put("ok", s.ok.sum());
            m.put("unavailable", s.unavailable.sum());
            m.put("unsupported", s.unsupported.sum());
            m.put("repaired", s.repaired.sum());
            m.put("avgMs", calls == 0 ? 0 : s.totalMs.sum() / calls);
            m.put("lastConfidence", s.lastConfidence);
            m.put("lastEpochMillis", s.lastEpochMillis);
            out.put(intent, m);
        });
        return out;
    }

    private static final class IntentStats {
        final LongAdder calls = new LongAdder();
        final LongAdder ok = new LongAdder();
        final LongAdder unavailable = new LongAdder();
        final LongAdder unsupported = new LongAdder();
        final LongAdder repaired = new LongAdder();
        final LongAdder totalMs = new LongAdder();
        volatile double lastConfidence;
        volatile long lastEpochMillis;
    }
}
