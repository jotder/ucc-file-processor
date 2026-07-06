package com.gamma.agent.model;

import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelResponse;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.model.ModelTier;
import com.eoiagent.model.Lc4jChatGateway;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.ollama.OllamaChatModel;

import java.time.Duration;
import java.util.EnumMap;

/**
 * The local Ollama {@link ModelProvider}: one instance binds a single {@link ModelTier}/model from a
 * {@link ModelProfile}. Same public surface as the agent-kernel class it replaces (the kernel is
 * discontinued, 2026-07-07), now transported through an eoiagent {@link Lc4jChatGateway} via
 * {@link EoiGatewayModelProvider}. The lc4j {@code OllamaChatModel}s are still built here — not by
 * eoiagent's stock adapter — to keep the native {@code format=json} pair and the client timeout.
 *
 * <h3>Lazy &amp; abstain-safe</h3>
 * Chat models are built on first {@code generate} only — never in the constructor — so merely
 * constructing providers touches no network. {@link #available()} is a pure configuration check.
 */
public final class OllamaModelProvider implements ModelProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final ModelProfile profile;
    private final ModelTier tier;
    private final String modelName;
    private final EoiGatewayModelProvider delegate;

    public OllamaModelProvider(ModelProfile profile, ModelTier tier) {
        this.profile = profile;
        this.tier = tier;
        this.modelName = profile.model(tier);
        this.delegate = new EoiGatewayModelProvider(
                "ollama:" + modelName + " (" + tier + ")", this::available,
                () -> gateway(ResponseFormat.TEXT), () -> gateway(ResponseFormat.JSON));
    }

    /** A {@link ModelRouter} backed by one {@link OllamaModelProvider} per tier of the profile. */
    public static ModelRouter routerFor(ModelProfile profile) {
        EnumMap<ModelTier, ModelProvider> m = new EnumMap<>(ModelTier.class);
        for (ModelTier t : ModelTier.values()) m.put(t, new OllamaModelProvider(profile, t));
        return ModelRouter.of(m);
    }

    /** A router from the environment-resolved profile. */
    public static ModelRouter fromEnvironment() {
        return routerFor(ModelProfile.fromEnvironment());
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean available() {
        return profile.enabled()
                && modelName != null && !modelName.isBlank()
                && profile.baseUrl() != null && !profile.baseUrl().isBlank();
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        return delegate.generate(request);
    }

    private LlmGateway gateway(ResponseFormat format) {
        return new Lc4jChatGateway(
                OllamaChatModel.builder()
                        .baseUrl(profile.baseUrl())
                        .modelName(modelName)
                        .timeout(TIMEOUT)
                        .responseFormat(format)
                        .build(),
                null,
                new ModelInfo("ollama", modelName, true));
    }
}
