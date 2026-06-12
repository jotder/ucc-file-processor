package com.gamma.agent.hosted;

import com.gamma.agent.model.HostedProviderPlugin;
import com.gamma.agent.model.ProviderSettings;
import com.gamma.agentkernel.model.ModelProvider;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.agentkernel.model.ModelTier;

import java.util.EnumMap;
import java.util.Set;

/**
 * The hosted-provider ServiceLoader contribution (v4.1): one {@link LangChain4jChatProvider} per
 * {@link ModelTier} for whichever provider the settings select. Discovered by the agent module's
 * {@code ModelProviderFactory}; this jar being on the classpath is what makes the hosted ids
 * selectable in the settings screen.
 */
public final class LangChain4jProviderPlugin implements HostedProviderPlugin {

    private static final Set<String> PROVIDERS =
            Set.of("anthropic", "openai", "gemini", "grok", "llamacpp");

    @Override
    public Set<String> providers() {
        return PROVIDERS;
    }

    @Override
    public ModelRouter createRouter(ProviderSettings settings, String apiKey) {
        EnumMap<ModelTier, ModelProvider> byTier = new EnumMap<>(ModelTier.class);
        for (ModelTier t : ModelTier.values()) {
            byTier.put(t, new LangChain4jChatProvider(settings.provider(), settings, apiKey, t));
        }
        return ModelRouter.of(byTier);
    }
}
