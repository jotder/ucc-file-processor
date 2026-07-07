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
import com.gamma.service.SourceService;

import java.util.List;

/**
 * Inspecto's {@link ApplicationPack} (AGT-5, P0 — {@code docs/superpower/embedded-intelligence-plan.md}
 * §1): the eight seams {@code PlatformBuilder.pack(new InspectoPack(service)).start()} assembles
 * into a ready {@code AgentService}. P0 scope deliberately ships:
 *
 * <ul>
 *   <li><b>no RAG corpus</b> ({@link #knowledgeSources()} is empty) — grounding comes from the
 *       read tool belt ({@code glossary_lookup}/{@code docs_search}/{@code status_get}) instead,
 *       which avoids standing up the ONNX embedding + ingestion path before it's verified
 *       offline-safe in CI; a fast-follow once that's proven.</li>
 *   <li><b>QA only</b> — the eoiagent host layer ({@code DefaultAgentSession}) always drives
 *       {@code GoalKind.QA} today, so {@link InspectoPromptProfile}'s other per-kind prompts are
 *       groundwork for P1/P2, not yet exercised.</li>
 *   <li><b>local models only</b> ({@code DeploymentProfile.OFFLINE}, see {@link InspectoPackConfig})
 *       — a hosted-provider companion module (mirroring {@code inspecto-agent-hosted}) is a later
 *       phase.</li>
 * </ul>
 */
public final class InspectoPack implements ApplicationPack {

    private final SourceService service;

    public InspectoPack(SourceService service) {
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
        return List.of();
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
