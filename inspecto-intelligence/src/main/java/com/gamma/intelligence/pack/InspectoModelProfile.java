package com.gamma.intelligence.pack;

import com.eoiagent.app.ModelProfile;
import com.eoiagent.app.ModelSelection;
import com.eoiagent.app.RoutingPolicy;
import com.gamma.model.ModelSettings;
import com.gamma.model.ModelSettingsStore;

import java.util.List;

/**
 * Bridges the pack's {@link ModelProfile} from the core model settings the assist-agent settings screen
 * writes (S9: {@link ModelSettingsStore}/{@link ModelSettings} in core — no compile dep on
 * {@code inspecto-agent}), so operators configure one model provider for both the reflex skills and
 * embedded-intelligence sessions. Falls back to the local-Ollama default when nothing has been saved yet.
 */
final class InspectoModelProfile implements ModelProfile {

    private final ModelSettings settings;

    InspectoModelProfile() {
        this.settings = ModelSettingsStore.load().orElseGet(() -> ModelSettings.defaults("ollama"));
    }

    @Override
    public ModelSelection chat() {
        return new ModelSelection(settings.provider(), settings.model("medium"),
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
