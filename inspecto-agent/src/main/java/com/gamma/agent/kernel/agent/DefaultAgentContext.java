package com.gamma.agent.kernel.agent;

import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.observe.AuditSink;
import com.gamma.agent.kernel.retrieve.Retriever;
import com.gamma.agent.kernel.tool.ToolRegistry;

import java.util.Map;
import java.util.Optional;

/** The immutable default {@link AgentContext} built by {@link AgentContext.Builder}. */
final class DefaultAgentContext implements AgentContext {

    private final Map<Class<?>, Object> handles;
    private final ToolRegistry tools;
    private final ModelRouter models;
    private final Retriever retriever;
    private final AuditSink audit;
    private final String tenantId;
    private final ModelTier tierOverride; // null = no escalation override

    DefaultAgentContext(Map<Class<?>, Object> handles, ToolRegistry tools, ModelRouter models,
                        Retriever retriever, AuditSink audit, String tenantId, ModelTier tierOverride) {
        this.handles = handles;
        this.tools = tools;
        this.models = models;
        this.retriever = retriever;
        this.audit = audit;
        this.tenantId = tenantId;
        this.tierOverride = tierOverride;
    }

    @Override
    public <T> Optional<T> handle(Class<T> type) {
        return Optional.ofNullable(type.cast(handles.get(type)));
    }

    @Override public ToolRegistry tools() { return tools; }
    @Override public ModelRouter models() { return models; }
    @Override public Retriever retriever() { return retriever; }
    @Override public AuditSink audit() { return audit; }
    @Override public Optional<String> tenantId() { return Optional.ofNullable(tenantId); }

    @Override
    public ModelTier effectiveTier(ModelTier defaultTier) {
        return (tierOverride != null) ? tierOverride : defaultTier;
    }

    @Override
    public AgentContext withEffectiveTier(ModelTier tier) {
        return new DefaultAgentContext(handles, tools, models, retriever, audit, tenantId, tier);
    }
}
