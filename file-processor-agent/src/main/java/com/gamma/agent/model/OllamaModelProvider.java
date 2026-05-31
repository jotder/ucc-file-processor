package com.gamma.agent.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j-backed local model provider over Ollama (v3.3.0). One instance is bound to a single
 * {@link ModelTier}/model name from an {@link AssistProfile}.
 *
 * <h3>Lazy & abstain-safe</h3>
 * The {@link ChatModel} is built on first {@link #generate} only — never in the constructor — so
 * merely constructing providers (e.g. when the agent is discovered in a CI test) touches no
 * network. {@link #available()} is a pure configuration check; with the profile disabled or no
 * model mapped, it returns {@code false} and {@link #generate} is never reached.
 */
public final class OllamaModelProvider implements ModelProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final AssistProfile profile;
    private final ModelTier tier;
    private final String modelName;

    // Cached lazily; one model per response format (text vs json). Volatile for safe publication.
    private volatile ChatModel textModel;
    private volatile ChatModel jsonModel;

    public OllamaModelProvider(AssistProfile profile, ModelTier tier) {
        this.profile = profile;
        this.tier = tier;
        this.modelName = profile.model(tier);
    }

    @Override
    public String name() {
        return "ollama:" + modelName + " (" + tier + ")";
    }

    @Override
    public boolean available() {
        return profile.enabled()
                && modelName != null && !modelName.isBlank()
                && profile.baseUrl() != null && !profile.baseUrl().isBlank();
    }

    @Override
    public String generate(ModelRequest request) {
        if (!available())
            throw new IllegalStateException("ollama provider not available for tier " + tier
                    + " (assist disabled or no model mapped)");
        ChatModel model = request.jsonFormat() ? jsonModel() : textModel();
        List<ChatMessage> messages = new ArrayList<>(2);
        if (request.system() != null && !request.system().isBlank())
            messages.add(SystemMessage.from(request.system()));
        messages.add(UserMessage.from(request.prompt()));
        ChatResponse response = model.chat(messages);
        return response.aiMessage().text();
    }

    private ChatModel textModel() {
        ChatModel m = textModel;
        if (m == null) {
            synchronized (this) {
                if ((m = textModel) == null) m = textModel = build(ResponseFormat.TEXT);
            }
        }
        return m;
    }

    private ChatModel jsonModel() {
        ChatModel m = jsonModel;
        if (m == null) {
            synchronized (this) {
                if ((m = jsonModel) == null) m = jsonModel = build(ResponseFormat.JSON);
            }
        }
        return m;
    }

    private ChatModel build(ResponseFormat format) {
        return OllamaChatModel.builder()
                .baseUrl(profile.baseUrl())
                .modelName(modelName)
                .timeout(TIMEOUT)
                .responseFormat(format)
                .build();
    }
}
