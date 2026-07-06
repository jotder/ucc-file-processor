package com.gamma.agent.hosted;

import com.gamma.agent.model.EoiGatewayModelProvider;
import com.gamma.agent.model.ProviderSettings;
import com.gamma.agent.kernel.error.ModelError;
import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelResponse;
import com.gamma.agent.kernel.model.ModelTier;
import com.eoiagent.model.Lc4jChatGateway;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * One hosted {@link ModelProvider} (v4.1): binds a single provider id + {@link ModelTier}/model from
 * {@link ProviderSettings}. Anthropic, OpenAI, and Gemini use their first-party LangChain4j modules;
 * Grok and the llama.cpp server ride the OpenAI-compatible client with a custom base URL. This class
 * now only <em>builds</em> the lc4j chat model (keeping the per-request timeout); request/response
 * mapping and the JSON system-prompt constraint moved into the shared eoiagent bridge
 * ({@link EoiGatewayModelProvider} over {@link Lc4jChatGateway}) when agent-kernel was replaced
 * (2026-07-07).
 *
 * <h3>Lazy &amp; abstain-safe</h3>
 * The chat model is built on first {@code generate} only — never in the constructor — so merely
 * constructing providers touches no network and needs no key. {@link #available()} is a pure config
 * check (model mapped + key present, or base URL present for the keyless llama.cpp server).
 */
public final class LangChain4jChatProvider implements ModelProvider {

    private final String providerId;
    private final ProviderSettings settings;
    private final String apiKey;
    private final String modelName;
    private final EoiGatewayModelProvider delegate;

    public LangChain4jChatProvider(String providerId, ProviderSettings settings, String apiKey,
                                   ModelTier tier) {
        this.providerId = providerId;
        this.settings = settings;
        this.apiKey = apiKey;
        this.modelName = settings.model(tier);
        // No JSON gateway: hosted providers keep the uniform system-prompt JSON constraint.
        this.delegate = new EoiGatewayModelProvider(
                providerId + ":" + modelName + " (" + tier + ")", this::available,
                this::gateway, null);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean available() {
        if (modelName == null || modelName.isBlank()) return false;
        if (keyless()) return baseUrl() != null && !baseUrl().isBlank();
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        return delegate.generate(request);
    }

    /** The llama.cpp server takes no key — only a reachable OpenAI-compatible base URL. */
    private boolean keyless() {
        return "llamacpp".equals(providerId);
    }

    private String baseUrl() {
        return settings.baseUrl() != null ? settings.baseUrl()
                : ProviderSettings.defaultBaseUrl(providerId);
    }

    private LlmGateway gateway() {
        return new Lc4jChatGateway(build(), null,
                new ModelInfo(providerId, modelName, settings.local()));
    }

    private ChatModel build() {
        Duration timeout = Duration.ofSeconds(settings.timeoutSeconds());
        return switch (providerId) {
            case "anthropic" -> AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .maxTokens(4096)
                    .timeout(timeout)
                    .build();
            case "gemini" -> GoogleAiGeminiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .timeout(timeout)
                    .build();
            // OpenAI proper (default base URL), Grok (api.x.ai), llama.cpp (localhost server).
            case "openai", "grok", "llamacpp" -> {
                var b = OpenAiChatModel.builder()
                        .apiKey(keyless() && apiKey == null ? "unused" : apiKey)
                        .modelName(modelName)
                        .timeout(timeout);
                String url = baseUrl();
                if (url != null && !url.isBlank()) b.baseUrl(url);
                yield b.build();
            }
            default -> throw new ModelError("unsupported hosted provider '" + providerId + "'");
        };
    }
}
