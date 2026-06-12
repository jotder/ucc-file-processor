package com.gamma.agent.hosted;

import com.gamma.agent.model.HostedProviderPlugin;
import com.gamma.agent.model.ModelProviderFactory;
import com.gamma.agent.model.ProviderSettings;
import com.gamma.agentkernel.model.ModelProvider;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.agentkernel.model.ModelTier;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hosted-provider plugin (v4.1): discovery, per-tier routing, and config-only availability. */
class LangChain4jProviderPluginTest {

    @Test
    void discoveredViaServiceLoader() {
        boolean found = ServiceLoader.load(HostedProviderPlugin.class).stream()
                .anyMatch(p -> p.type() == LangChain4jProviderPlugin.class);
        assertTrue(found, "META-INF/services registration must expose the plugin");
        // And the factory therefore lists the hosted ids on this classpath.
        assertTrue(ModelProviderFactory.availableProviders().contains("anthropic"));
        assertTrue(ModelProviderFactory.availableProviders().contains("grok"));
    }

    @Test
    void routesEachTierToTheConfiguredModel() {
        ProviderSettings s = ProviderSettings.defaults("anthropic");
        ModelRouter r = new LangChain4jProviderPlugin().createRouter(s, "sk-test");
        assertEquals("anthropic:claude-haiku-4-5 (SMALL)", r.providerFor(ModelTier.SMALL).name());
        assertEquals("anthropic:claude-opus-4-8 (LARGE)", r.providerFor(ModelTier.LARGE).name());
        assertTrue(r.anyAvailable(), "key + model mapped ⇒ available (config check only)");
    }

    @Test
    void hostedProviderWithoutKeyIsUnavailable() {
        ModelRouter r = new LangChain4jProviderPlugin()
                .createRouter(ProviderSettings.defaults("openai"), null);
        assertFalse(r.anyAvailable(), "no key ⇒ abstain, never a network call");
    }

    @Test
    void llamaCppIsKeylessButNeedsABaseUrl() {
        LangChain4jProviderPlugin plugin = new LangChain4jProviderPlugin();
        ModelProvider withDefaultUrl = plugin
                .createRouter(ProviderSettings.defaults("llamacpp"), null)
                .providerFor(ModelTier.MEDIUM);
        assertTrue(withDefaultUrl.available(), "default localhost base URL suffices, no key needed");

        ProviderSettings noModel = new ProviderSettings("llamacpp", "http://localhost:8080/v1",
                null, Map.of(), 0);
        assertFalse(plugin.createRouter(noModel, null).providerFor(ModelTier.MEDIUM).available(),
                "unmapped tier stays unavailable");
    }
}
