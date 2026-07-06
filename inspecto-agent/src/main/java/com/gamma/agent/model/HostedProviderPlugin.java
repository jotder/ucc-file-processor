package com.gamma.agent.model;

import com.gamma.agent.kernel.model.ModelRouter;

import java.util.Set;

/**
 * ServiceLoader SPI for hosted model providers (v4.1). The optional
 * {@code file-processor-agent-hosted} module contributes an implementation backing the hosted
 * provider ids (Anthropic / OpenAI / Gemini / Grok and the OpenAI-compatible llama.cpp server);
 * without that jar on the classpath only local Ollama is selectable — the air-gapped packaging
 * guarantee stays intact ({@link ModelProviderFactory} simply finds no plugin).
 */
public interface HostedProviderPlugin {

    /** Provider ids this plugin can build (subset of {@link ProviderSettings#knownProviders()}). */
    Set<String> providers();

    /**
     * Build a tier-routing {@link ModelRouter} for the given settings. {@code apiKey} is the
     * already-resolved secret (session store / env) — may be {@code null}, in which case the
     * resulting providers report {@code available() == false} rather than fail.
     */
    ModelRouter createRouter(ProviderSettings settings, String apiKey);
}
