package com.gamma.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 unit tests for the v3.3.0 model seam: the deterministic fake, tier routing, and — critically —
 * that an {@link OllamaModelProvider} is <b>abstain-safe</b> (reports unavailable and performs no
 * network I/O) until a deployment explicitly enables the assist layer. These run CPU-only with no
 * Ollama present.
 */
class ModelSeamTest {

    @Test
    void fakeProviderIsDeterministic() {
        FakeModelProvider fake = FakeModelProvider.echo();
        assertTrue(fake.available());
        String a = fake.generate(ModelRequest.text(ModelTier.MEDIUM, "sys", "hello"));
        String b = fake.generate(ModelRequest.text(ModelTier.MEDIUM, "sys", "hello"));
        assertEquals("ECHO:hello", a);
        assertEquals(a, b, "same input -> same output");
    }

    @Test
    void downProviderAbstainsAndThrowsOnUse() {
        FakeModelProvider down = FakeModelProvider.down();
        assertFalse(down.available());
        assertThrows(IllegalStateException.class,
                () -> down.generate(ModelRequest.text(ModelTier.SMALL, null, "x")));
    }

    @Test
    void routerResolvesEveryTierToTheInjectedFake() {
        FakeModelProvider fake = FakeModelProvider.canned("ok");
        ModelRouter router = ModelRouter.of(fake);
        for (ModelTier t : ModelTier.values())
            assertSame(fake, router.provider(t), "tier " + t + " -> injected fake");
        assertTrue(router.anyAvailable());
    }

    @Test
    void unmappedTierFallsBackToUnavailableNeverNull() {
        ModelRouter empty = new ModelRouter(java.util.Map.of());
        ModelProvider p = empty.provider(ModelTier.LARGE);
        assertNotNull(p);
        assertFalse(p.available());
        assertFalse(empty.anyAvailable());
    }

    @Test
    void ollamaProviderAbstainsWhenAssistDisabled() {
        // Default bundles ship disabled — the CI / vanilla case. No model is contacted.
        AssistProfile disabled = AssistProfile.CPU_ONLY;
        assertFalse(disabled.enabled());
        OllamaModelProvider p = new OllamaModelProvider(disabled, ModelTier.MEDIUM);
        assertFalse(p.available(), "disabled profile -> not available, no network");
        assertThrows(IllegalStateException.class,
                () -> p.generate(ModelRequest.text(ModelTier.MEDIUM, null, "x")),
                "generate is illegal while unavailable (guards against accidental network I/O)");
    }

    @Test
    void ollamaProviderBecomesAvailableOnlyWhenEnabledAndMapped() {
        AssistProfile enabled = new AssistProfile("cpu-only", AssistProfile.DEFAULT_BASE_URL, true,
                AssistProfile.PRODUCTION.models());
        assertTrue(new OllamaModelProvider(enabled, ModelTier.MEDIUM).available());
        assertTrue(new OllamaModelProvider(enabled, ModelTier.LARGE).available());
        // A profile with no mapping for a tier stays unavailable even when enabled.
        AssistProfile noLarge = new AssistProfile("partial", AssistProfile.DEFAULT_BASE_URL, true,
                java.util.Map.of(ModelTier.SMALL, "qwen2.5:3b"));
        assertFalse(new OllamaModelProvider(noLarge, ModelTier.LARGE).available());
    }

    @Test
    void builtInProfilesMapEveryTier() {
        for (AssistProfile profile : new AssistProfile[]{
                AssistProfile.CPU_ONLY, AssistProfile.DEV_LAPTOP, AssistProfile.PRODUCTION}) {
            for (ModelTier t : ModelTier.values())
                assertNotNull(profile.model(t), profile.name() + " maps " + t);
        }
        assertEquals("qwen2.5:14b", AssistProfile.PRODUCTION.model(ModelTier.LARGE),
                "production upgrades the heavy tier to 14B");
    }

    @Test
    void fromEnvironmentDefaultsToCpuOnlyDisabled() {
        // With no -Dassist.* set, the resolved profile is cpu-only and disabled (abstain-by-default).
        AssistProfile env = AssistProfile.fromEnvironment();
        assertEquals("cpu-only", env.name());
        assertFalse(env.enabled(), "assist off unless explicitly enabled");
        assertFalse(ModelRouter.fromEnvironment().anyAvailable());
    }
}
