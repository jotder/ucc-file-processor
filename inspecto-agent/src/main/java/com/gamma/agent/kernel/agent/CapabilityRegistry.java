package com.gamma.agent.kernel.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The id→{@link Capability} table. {@link #dispatch} resolves the request's capability id and runs it;
 * an unknown id yields {@link AgentResult#unsupported(String)}. This is <em>plain</em> dispatch — no
 * confidence/escalation/audit. The assembled <em>synchronous</em> pipeline is the {@code SyncOrchestrator}
 * in the ring-2 {@code agent-orchestration} module (R1; ADR-0009); the async/streaming orchestrator
 * variants remain deferred until a second consumer (CVVE/CxO) shapes them.
 */
public interface CapabilityRegistry {

    /** The capability registered under {@code id}, if any. */
    Optional<Capability> get(String id);

    /** The registered capability ids. */
    Set<String> ids();

    /** Run the capability bound to {@code request.capabilityId()}, or report it unsupported. */
    AgentResult dispatch(AgentRequest request, AgentContext ctx);

    /** An immutable registry over the given capabilities (keyed by {@code spec().id()}; last one wins). */
    static CapabilityRegistry of(Collection<Capability> capabilities) {
        Map<String, Capability> byId = new LinkedHashMap<>();
        if (capabilities != null) {
            for (Capability c : capabilities) byId.put(c.spec().id(), c);
        }
        Map<String, Capability> copy = Map.copyOf(byId);
        return new CapabilityRegistry() {
            @Override public Optional<Capability> get(String id) { return Optional.ofNullable(copy.get(id)); }
            @Override public Set<String> ids() { return copy.keySet(); }
            @Override public AgentResult dispatch(AgentRequest request, AgentContext ctx) {
                Capability c = copy.get(request.capabilityId());
                return (c == null) ? AgentResult.unsupported(request.capabilityId()) : c.run(request, ctx);
            }
        };
    }
}
