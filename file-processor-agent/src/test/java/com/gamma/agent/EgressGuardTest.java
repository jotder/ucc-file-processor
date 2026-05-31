package com.gamma.agent;

import com.gamma.agent.model.ModelRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4 security guard (R3 — fail-closed egress by packaging). M3 ships <b>local-only</b>: no
 * hosted-model SDK is on the agent classpath, so the router <em>cannot construct</em> a hosted
 * provider — the offline guarantee is physical, not a config flag. These tests assert the packaging
 * invariant directly, and that the default environment is abstain (no model contacted in CI).
 */
class EgressGuardTest {

    /** Classes that would only be present if a hosted-model SDK had leaked onto the classpath. */
    private static final String[] HOSTED_SDK_CLASSES = {
            "dev.langchain4j.model.openai.OpenAiChatModel",
            "dev.langchain4j.model.anthropic.AnthropicChatModel",
            "dev.langchain4j.model.googleai.GoogleAiGeminiChatModel",
            "dev.langchain4j.model.vertexai.VertexAiGeminiChatModel",
            "com.openai.client.OpenAIClient",
    };

    @Test
    void noHostedModelSdkOnClasspath() {
        for (String fqcn : HOSTED_SDK_CLASSES) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(fqcn),
                    "hosted SDK leaked onto the air-gapped classpath: " + fqcn);
        }
    }

    @Test
    void localOllamaProviderIsPresent() throws Exception {
        // Sanity: the local path we DO ship is on the classpath.
        assertNotNull(Class.forName("dev.langchain4j.model.ollama.OllamaChatModel"));
    }

    @Test
    void defaultEnvironmentContactsNoModel() {
        // No -Dassist.* configured (the CI/vanilla case) -> nothing is available, so nothing is called.
        assertFalse(ModelRouter.fromEnvironment().anyAvailable(),
                "assist is off by default — local-first, opt-in");
    }
}
