package com.gamma.intelligence;

import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.OllamaChatAdapter;
import com.eoiagent.model.OpenAiCompatibleChatAdapter;
import com.eoiagent.model.StubLlmGateway;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.model.AssistModelSettings;
import com.gamma.agent.model.ProviderSettings;

/**
 * Builds the {@link LlmGateway} passed explicitly to {@code PlatformBuilder.llmGateway(...)} —
 * bypassing the platform's own provider routing entirely, so an unsupported/hosted provider
 * string in {@link com.gamma.intelligence.pack.InspectoModelProfile} never breaks assembly. Reads
 * the same {@link AssistModelSettings} the reflex-layer settings screen writes, so one screen
 * configures both agent modules.
 *
 * <p>P0 only wires local providers (ollama, llama.cpp) for real — a hosted provider falls back to
 * a deterministic offline stub with an explanatory reply, since this module deliberately carries
 * no hosted-model SDK (see the pack-level Javadoc on air-gap discipline).
 */
final class GatewayFactory {

    private GatewayFactory() {
    }

    static LlmGateway build() {
        ProviderSettings settings = AssistModelSettings.load().orElseGet(() -> ProviderSettings.defaults("ollama"));
        String modelId = settings.model(ModelTier.MEDIUM);
        if (settings.local() && settings.baseUrl() != null && modelId != null) {
            return switch (settings.provider()) {
                case "ollama" -> new OllamaChatAdapter(settings.baseUrl(), modelId);
                case "llamacpp" -> new OpenAiCompatibleChatAdapter(settings.baseUrl(), modelId, null);
                default -> offlineStub(settings);
            };
        }
        return offlineStub(settings);
    }

    private static LlmGateway offlineStub(ProviderSettings settings) {
        return StubLlmGateway.builder()
                .defaultReplyText("No reachable local model is configured for the embedded intelligence "
                        + "agent (provider '" + settings.provider() + "'); hosted providers are not wired "
                        + "into file-processor-intelligence yet. Configure a local Ollama endpoint under "
                        + "Assist Settings.")
                .build();
    }
}
