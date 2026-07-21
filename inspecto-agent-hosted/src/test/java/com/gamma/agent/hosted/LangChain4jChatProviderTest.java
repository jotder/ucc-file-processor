package com.gamma.agent.hosted;

import com.gamma.agent.model.ProviderSettings;
import com.gamma.agent.kernel.model.ModelTier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for the hosted provider's config-only {@link LangChain4jChatProvider#available()} check —
 * the abstain-safe gate that decides whether a key/model/base-url combination could serve, without ever
 * building a client or touching the network. Constructing a provider must itself be network-free.
 */
class LangChain4jChatProviderTest {

    @Test
    void availableWhenModelMappedAndKeyPresent() {
        LangChain4jChatProvider p = new LangChain4jChatProvider(
                "anthropic", ProviderSettings.defaults("anthropic"), "sk-test", ModelTier.SMALL);
        assertTrue(p.available(), "model mapped + key present ⇒ available (config check only)");
        assertTrue(p.name().contains("anthropic"), "name carries the provider id");
    }

    @Test
    void unavailableWhenKeyMissingForAKeyedProvider() {
        LangChain4jChatProvider p = new LangChain4jChatProvider(
                "anthropic", ProviderSettings.defaults("anthropic"), null, ModelTier.SMALL);
        assertFalse(p.available(), "no key ⇒ abstain, never a network call");
    }

    @Test
    void unavailableWhenTierHasNoModel() {
        // Empty tier map ⇒ model(tier) is null ⇒ unavailable even with a key.
        ProviderSettings noModel = new ProviderSettings("anthropic", null, null, Map.of(), 0);
        LangChain4jChatProvider p = new LangChain4jChatProvider("anthropic", noModel, "sk-test", ModelTier.SMALL);
        assertFalse(p.available(), "unmapped tier ⇒ unavailable");
    }

    @Test
    void keylessLlamaCppNeedsOnlyABaseUrl() {
        LangChain4jChatProvider p = new LangChain4jChatProvider(
                "llamacpp", ProviderSettings.defaults("llamacpp"), null, ModelTier.MEDIUM);
        assertTrue(p.available(), "llama.cpp is keyless — the default localhost base URL suffices");
    }
}
