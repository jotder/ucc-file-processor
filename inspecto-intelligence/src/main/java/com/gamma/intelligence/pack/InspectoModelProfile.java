package com.gamma.intelligence.pack;

import com.eoiagent.app.ModelProfile;
import com.eoiagent.app.ModelSelection;
import com.eoiagent.app.RoutingPolicy;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.model.AssistModelSettings;
import com.gamma.agent.model.ProviderSettings;

import java.util.List;

/**
 * Bridges the pack's {@link ModelProfile} from the existing assist-agent settings screen
 * ({@link AssistModelSettings}/{@link ProviderSettings}) so operators configure one model
 * provider for both the reflex skills and embedded-intelligence sessions. Falls back to the
 * local-Ollama default when no settings have been saved yet.
 */
final class InspectoModelProfile implements ModelProfile {

    private final ProviderSettings settings;

    InspectoModelProfile() {
        this.settings = AssistModelSettings.load().orElseGet(() -> ProviderSettings.defaults("ollama"));
    }

    @Override
    public ModelSelection chat() {
        return new ModelSelection(settings.provider(), settings.model(ModelTier.MEDIUM),
                settings.baseUrl(), settings.local());
    }

    @Override
    public ModelSelection embedding() {
        // Unused while knowledgeSources() is empty (P0 has no RAG ingestion — grounding comes from
        // the read tool belt instead); kept valid/consistent for when P1 adds a corpus.
        return new ModelSelection("onnx-all-minilm", "all-MiniLM-L6-v2", null, true);
    }

    @Override
    public RoutingPolicy routing() {
        // P0 always runs DeploymentProfile.OFFLINE (see InspectoPackConfig), which forbids hosted
        // fallback regardless of the configured provider. A hosted provider still reaches the
        // model through the explicit LlmGateway InspectoIntelligenceAgent builds — never through
        // the platform's own internal routing, which this policy governs.
        return new RoutingPolicy(List.of(settings.provider()), false);
    }
}
