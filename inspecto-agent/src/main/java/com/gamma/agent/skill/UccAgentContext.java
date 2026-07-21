package com.gamma.agent.skill;

import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.observe.AuditSink;
import com.gamma.agent.kernel.retrieve.DocRetriever;
import com.gamma.agent.kernel.retrieve.Retriever;
import com.gamma.agent.kernel.tool.ToolRegistry;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.report.ReportService;
import com.gamma.etl.StatusStore;

import java.util.List;
import java.util.Optional;

/**
 * The UCC {@link AgentContext}: the typed handles a capability is allowed to read, captured once by
 * the agent in {@code init()} and handed to every invocation. Read-only by construction — the M1
 * metadata {@link #catalog}, the {@link #reports} and {@link #statusStore} control-plane reads, the
 * read-only {@link #configs} seam, the bundled {@link #docs} corpus, and the {@link #models} router.
 * No write-bearing handle is exposed — the agent proposes, it never disposes.
 *
 * <p>Implements the kernel {@link AgentContext}: app-specific handles via {@link #handle(Class)},
 * the always-present kernel infrastructure ({@link #tools()}, {@link #models()}, {@link #retriever()},
 * {@link #audit()}), and the escalation ↔ tier seam ({@link #effectiveTier}/{@link #withEffectiveTier}).
 */
public final class UccAgentContext implements AgentContext {

    private final MetadataGraphService catalog;
    private final ReportService reports;
    private final StatusStore statusStore;
    private final DocRetriever docs;
    private final ModelRouter models;
    private final ConfigSource configs;
    private final ToolRegistry tools;
    private final AuditSink audit;
    private final ModelTier tierOverride; // null = no escalation override

    /** The 6-arg constructor tests use: default tools, a no-op audit sink, no tier override. */
    public UccAgentContext(MetadataGraphService catalog, ReportService reports, StatusStore statusStore,
                           DocRetriever docs, ModelRouter models, ConfigSource configs) {
        this(catalog, reports, statusStore, docs, models, configs, AuditSink.NONE);
    }

    /** The 7-arg constructor the agent uses: default tools, the supplied audit sink, no tier override. */
    public UccAgentContext(MetadataGraphService catalog, ReportService reports, StatusStore statusStore,
                           DocRetriever docs, ModelRouter models, ConfigSource configs, AuditSink audit) {
        this(catalog, reports, statusStore, docs, models, configs,
                ToolRegistry.of(List.of(new SqlOracleTool(), new AlertRuleTool())),
                audit == null ? AuditSink.NONE : audit, null);
    }

    private UccAgentContext(MetadataGraphService catalog, ReportService reports, StatusStore statusStore,
                            DocRetriever docs, ModelRouter models, ConfigSource configs,
                            ToolRegistry tools, AuditSink audit, ModelTier tierOverride) {
        this.catalog = catalog;
        this.reports = reports;
        this.statusStore = statusStore;
        this.docs = docs;
        this.models = models;
        this.configs = configs;
        this.tools = tools;
        this.audit = audit;
        this.tierOverride = tierOverride;
    }

    // ── UCC convenience accessors (the names the capabilities already call) ──────────────

    public MetadataGraphService catalog() { return catalog; }
    public ReportService reports() { return reports; }
    public StatusStore statusStore() { return statusStore; }
    public DocRetriever docs() { return docs; }
    public ConfigSource configs() { return configs; }

    // ── AgentContext ──────────────────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> handle(Class<T> type) {
        Object match = null;
        if (type == MetadataGraphService.class) match = catalog;
        else if (type == ReportService.class) match = reports;
        else if (type == StatusStore.class) match = statusStore;
        else if (type == ConfigSource.class) match = configs;
        else if (type == DocRetriever.class) match = docs;
        return Optional.ofNullable((T) match);
    }

    @Override public ToolRegistry tools() { return tools; }
    @Override public ModelRouter models() { return models; }
    @Override public Retriever retriever() { return docs; }
    @Override public AuditSink audit() { return audit; }
    @Override public Optional<String> tenantId() { return Optional.empty(); }

    @Override
    public ModelTier effectiveTier(ModelTier defaultTier) {
        return (tierOverride != null) ? tierOverride : defaultTier;
    }

    @Override
    public AgentContext withEffectiveTier(ModelTier tier) {
        return new UccAgentContext(catalog, reports, statusStore, docs, models, configs, tools, audit, tier);
    }
}
