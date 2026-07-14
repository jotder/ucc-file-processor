package com.gamma.intelligence.pack;

import com.eoiagent.app.ApplicationPack;
import com.eoiagent.app.KnowledgeSource;
import com.eoiagent.app.ModelProfile;
import com.eoiagent.app.NavigationCatalog;
import com.eoiagent.app.PackConfig;
import com.eoiagent.app.PackMetadata;
import com.eoiagent.app.PolicyProfile;
import com.eoiagent.app.PromptProfile;
import com.eoiagent.app.ToolProvider;
import com.eoiagent.core.AppId;
import com.gamma.service.CollectorService;

import java.util.List;

/**
 * Inspecto's {@link ApplicationPack} (AGT-5, P0 — {@code docs/superpower/embedded-intelligence-plan.md}
 * §1): the eight seams {@code PlatformBuilder.pack(new InspectoPack(service)).start()} assembles
 * into a ready {@code AgentService}. P0 scope deliberately ships:
 *
 * <ul>
 *   <li><b>a one-document RAG corpus</b> ({@link #knowledgeSources()}: just {@code docs/GLOSSARY.md},
 *       see {@link InspectoKnowledgeSources}) — proven offline-safe in CI before widening to the
 *       full OKF/docs corpus, a fast-follow. The read tool belt (in {@link InspectoToolProvider})
 *       still grounds everything else.</li>
 *   <li><b>QA only</b> — the eoiagent host layer ({@code DefaultAgentSession}) always drives
 *       {@code GoalKind.QA} today, so {@link InspectoPromptProfile}'s other per-kind prompts are
 *       groundwork for P1/P2, not yet exercised.</li>
 *   <li><b>local models only</b> ({@code DeploymentProfile.OFFLINE}, see {@link InspectoPackConfig})
 *       — a hosted-provider companion module (mirroring {@code inspecto-agent-hosted}) is a later
 *       phase.</li>
 * </ul>
 */
public final class InspectoPack implements ApplicationPack {

    private final CollectorService service;

    public InspectoPack(CollectorService service) {
        this.service = service;
    }

    @Override
    public PackMetadata metadata() {
        return new PackMetadata(new AppId("inspecto"), "Inspecto", "0.1.0");
    }

    @Override
    public ModelProfile modelProfile() {
        return new InspectoModelProfile();
    }

    @Override
    public List<KnowledgeSource> knowledgeSources() {
        return InspectoKnowledgeSources.collectors();
    }

    @Override
    public ToolProvider toolProvider() {
        return new InspectoToolProvider(service);
    }

    @Override
    public NavigationCatalog navigationCatalog() {
        return new InspectoNavigationCatalog();
    }

    @Override
    public PromptProfile promptProfile() {
        return new InspectoPromptProfile();
    }

    @Override
    public PolicyProfile policyProfile() {
        return new InspectoPolicyProfile();
    }

    @Override
    public PackConfig config() {
        return new InspectoPackConfig();
    }
}
