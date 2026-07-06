package com.gamma.agent.kernel.agent;

import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.observe.AuditSink;
import com.gamma.agent.kernel.retrieve.Retriever;
import com.gamma.agent.kernel.tool.ToolRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A read-only handle bag a capability is allowed to read (least-privilege). App-specific typed handles
 * are fetched via {@link #handle(Class)}; the kernel infrastructure ({@link #tools()}, {@link #models()},
 * {@link #retriever()}, {@link #audit()}) is always present. {@link #tenantId()} is opaque to the kernel.
 *
 * <p>{@link #effectiveTier(ModelTier)} is the escalation ↔ tier seam: capabilities call it instead of
 * reading their default tier directly, and an escalation policy hands the capability a context with a
 * raised tier via {@link #withEffectiveTier(ModelTier)}.
 */
public interface AgentContext {

    /** An app-specific typed handle, if registered (exact-type match). */
    <T> Optional<T> handle(Class<T> type);

    ToolRegistry tools();

    ModelRouter models();

    Retriever retriever();

    AuditSink audit();

    /** Opaque tenant id (CVVE sets it; UCC does not). */
    Optional<String> tenantId();

    /** The tier to generate at: the escalation override if set, else {@code defaultTier}. */
    ModelTier effectiveTier(ModelTier defaultTier);

    /** A copy of this context whose {@link #effectiveTier} returns {@code tier} (used by escalation). */
    AgentContext withEffectiveTier(ModelTier tier);

    static Builder builder() { return new Builder(); }

    /** Builds an immutable {@link AgentContext}. Infrastructure handles default to no-op/unavailable. */
    final class Builder {
        private final Map<Class<?>, Object> handles = new HashMap<>();
        private ToolRegistry tools = ToolRegistry.of(java.util.List.of());
        private ModelRouter models = ModelRouter.of(java.util.Map.of());
        private Retriever retriever = Retriever.NONE;
        private AuditSink audit = AuditSink.NONE;
        private String tenantId;

        public <T> Builder handle(Class<T> type, T instance) { handles.put(type, instance); return this; }
        public Builder tools(ToolRegistry tools) { this.tools = tools; return this; }
        public Builder models(ModelRouter models) { this.models = models; return this; }
        public Builder retriever(Retriever retriever) { this.retriever = retriever; return this; }
        public Builder audit(AuditSink audit) { this.audit = audit; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }

        public AgentContext build() {
            return new DefaultAgentContext(Map.copyOf(handles), tools, models, retriever, audit, tenantId, null);
        }
    }
}
