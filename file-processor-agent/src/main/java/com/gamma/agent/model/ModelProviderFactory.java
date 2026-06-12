package com.gamma.agent.model;

import com.gamma.agentkernel.model.ModelProvider;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.agentkernel.provider.ollama.ModelProfile;
import com.gamma.agentkernel.provider.ollama.OllamaModelProvider;

import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Maps {@link ProviderSettings} to a concrete {@link ModelRouter} (v4.1) — the one place a provider
 * id becomes a client. Local Ollama is built directly (the dependency this module always carries);
 * hosted ids are resolved through the {@link HostedProviderPlugin} ServiceLoader seam so hosted SDKs
 * stay out of the default classpath ({@code file-processor-agent-hosted} contributes them).
 *
 * <p>Construction never touches the network — every provider builds its client lazily and reports
 * {@code available()} from configuration alone (the kernel's abstain-safe contract).
 */
public final class ModelProviderFactory {

    private ModelProviderFactory() {}

    /**
     * The router for the persisted settings, or the legacy environment-resolved Ollama router when
     * no settings file exists — existing deployments keep working unchanged.
     */
    public static ModelRouter fromPersisted() {
        return AssistModelSettings.load()
                .map(ModelProviderFactory::create)
                .orElseGet(OllamaModelProvider::fromEnvironment);
    }

    /** Build a router for explicit settings. Unknown/unbacked hosted ids yield an unavailable router. */
    public static ModelRouter create(ProviderSettings settings) {
        if (settings == null) return OllamaModelProvider.fromEnvironment();
        String id = settings.provider();
        if ("ollama".equals(id)) {
            String baseUrl = settings.baseUrl() != null
                    ? settings.baseUrl() : ProviderSettings.defaultBaseUrl("ollama");
            var models = settings.models().isEmpty()
                    ? ProviderSettings.defaultModels("ollama") : settings.models();
            // enabled=true: choosing ollama in the settings IS the explicit opt-in.
            return OllamaModelProvider.routerFor(new ModelProfile("settings", baseUrl, true, models));
        }
        for (HostedProviderPlugin plugin : ServiceLoader.load(HostedProviderPlugin.class)) {
            if (plugin.providers().contains(id)) {
                return plugin.createRouter(settings, AssistModelSettings.resolveApiKey(settings));
            }
        }
        String why = ProviderSettings.knownProviders().contains(id)
                ? "provider '" + id + "' requires the file-processor-agent-hosted jar on the classpath"
                : "unknown model provider '" + id + "'";
        return tier -> ModelProvider.unavailable(why);
    }

    /** Provider ids selectable in this deployment: always ollama, plus whatever plugins contribute. */
    public static Set<String> availableProviders() {
        Set<String> out = new LinkedHashSet<>();
        out.add("ollama");
        for (HostedProviderPlugin plugin : ServiceLoader.load(HostedProviderPlugin.class)) {
            out.addAll(plugin.providers());
        }
        return out;
    }
}
