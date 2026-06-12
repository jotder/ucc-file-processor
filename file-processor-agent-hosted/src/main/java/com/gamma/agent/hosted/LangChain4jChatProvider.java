package com.gamma.agent.hosted;

import com.gamma.agent.model.ProviderSettings;
import com.gamma.agentkernel.error.ModelError;
import com.gamma.agentkernel.model.ModelProvider;
import com.gamma.agentkernel.model.ModelRequest;
import com.gamma.agentkernel.model.ModelResponse;
import com.gamma.agentkernel.model.ModelTier;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One LangChain4j-backed hosted {@link ModelProvider} (v4.1): binds a single provider id +
 * {@link ModelTier}/model from {@link ProviderSettings}. Anthropic, OpenAI, and Gemini use their
 * first-party LangChain4j modules; Grok and the llama.cpp server ride the OpenAI-compatible client
 * with a custom base URL.
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
    private final ModelTier tier;
    private final String modelName;

    private volatile ChatModel chatModel;

    public LangChain4jChatProvider(String providerId, ProviderSettings settings, String apiKey,
                                   ModelTier tier) {
        this.providerId = providerId;
        this.settings = settings;
        this.apiKey = apiKey;
        this.tier = tier;
        this.modelName = settings.model(tier);
    }

    @Override
    public String name() {
        return providerId + ":" + modelName + " (" + tier + ")";
    }

    @Override
    public boolean available() {
        if (modelName == null || modelName.isBlank()) return false;
        if (keyless()) return baseUrl() != null && !baseUrl().isBlank();
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        if (!available()) {
            throw new ModelError(providerId + " provider not available for tier " + tier
                    + " (no model mapped, or no API key resolved)");
        }
        try {
            List<ChatMessage> messages = new ArrayList<>(2);
            String system = request.system();
            if (request.jsonFormat()) {
                // Uniform JSON constraint across providers (matches the kernel's Gemini provider).
                String json = "Respond with valid JSON only, no prose or code fences.";
                system = (system == null || system.isBlank()) ? json : system + "\n\n" + json;
            }
            if (system != null && !system.isBlank()) messages.add(SystemMessage.from(system));
            messages.add(UserMessage.from(request.prompt()));
            ChatResponse response = chatModel().chat(messages);
            return new ModelResponse(response.aiMessage().text(),
                    tokens(response.tokenUsage(), true), tokens(response.tokenUsage(), false));
        } catch (ModelError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ModelError(providerId + " generation failed for tier " + tier, e);
        }
    }

    /** The llama.cpp server takes no key — only a reachable OpenAI-compatible base URL. */
    private boolean keyless() {
        return "llamacpp".equals(providerId);
    }

    private String baseUrl() {
        return settings.baseUrl() != null ? settings.baseUrl()
                : ProviderSettings.defaultBaseUrl(providerId);
    }

    private static int tokens(TokenUsage usage, boolean input) {
        if (usage == null) return -1;
        Integer v = input ? usage.inputTokenCount() : usage.outputTokenCount();
        return v == null ? -1 : v;
    }

    private ChatModel chatModel() {
        ChatModel m = chatModel;
        if (m == null) {
            synchronized (this) {
                if ((m = chatModel) == null) m = chatModel = build();
            }
        }
        return m;
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
