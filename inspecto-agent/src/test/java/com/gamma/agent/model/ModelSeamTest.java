package com.gamma.agent.model;

import com.gamma.agent.kernel.error.ModelError;
import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.model.ModelProfile;
import com.gamma.agent.model.OllamaModelProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the model seam, now backed by agent-kernel (U1): the deterministic fake, kernel tier
 * routing, and — critically — that a kernel {@link OllamaModelProvider} is <b>abstain-safe</b> (reports
 * unavailable and performs no network I/O) until a deployment enables the assist layer. CPU-only.
 */
class ModelSeamTest {

    @Test
    void fakeProviderIsDeterministic() {
        FakeModelProvider fake = FakeModelProvider.echo();
        assertTrue(fake.available());
        String a = fake.generate(ModelRequest.text(ModelTier.MEDIUM, "sys", "hello")).text();
        String b = fake.generate(ModelRequest.text(ModelTier.MEDIUM, "sys", "hello")).text();
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
            assertSame(fake, router.providerFor(t), "tier " + t + " -> injected fake");
        assertTrue(router.anyAvailable());
    }

    @Test
    void unmappedTierFallsBackToUnavailableNeverNull() {
        ModelRouter empty = ModelRouter.of(Map.of());
        ModelProvider p = empty.providerFor(ModelTier.LARGE);
        assertNotNull(p);
        assertFalse(p.available());
        assertFalse(empty.anyAvailable());
    }

    @Test
    void ollamaProviderAbstainsWhenAssistDisabled() {
        // Default bundles ship disabled — the CI / vanilla case. No model is contacted.
        ModelProfile disabled = ModelProfile.CPU_ONLY;
        assertFalse(disabled.enabled());
        OllamaModelProvider p = new OllamaModelProvider(disabled, ModelTier.MEDIUM);
        assertFalse(p.available(), "disabled profile -> not available, no network");
        assertThrows(ModelError.class,
                () -> p.generate(ModelRequest.text(ModelTier.MEDIUM, null, "x")),
                "generate is illegal while unavailable (guards against accidental network I/O)");
    }

    @Test
    void ollamaProviderBecomesAvailableOnlyWhenEnabledAndMapped() {
        ModelProfile enabled = new ModelProfile("cpu-only", ModelProfile.DEFAULT_BASE_URL, true,
                ModelProfile.PRODUCTION.models());
        assertTrue(new OllamaModelProvider(enabled, ModelTier.MEDIUM).available());
        assertTrue(new OllamaModelProvider(enabled, ModelTier.LARGE).available());
        // A profile with no mapping for a tier stays unavailable even when enabled.
        ModelProfile noLarge = new ModelProfile("partial", ModelProfile.DEFAULT_BASE_URL, true,
                Map.of(ModelTier.SMALL, "qwen2.5:3b"));
        assertFalse(new OllamaModelProvider(noLarge, ModelTier.LARGE).available());
    }

    @Test
    void builtInProfilesMapEveryTier() {
        for (ModelProfile profile : new ModelProfile[]{
                ModelProfile.CPU_ONLY, ModelProfile.DEV_LAPTOP, ModelProfile.PRODUCTION}) {
            for (ModelTier t : ModelTier.values())
                assertNotNull(profile.model(t), profile.name() + " maps " + t);
        }
        assertEquals("qwen2.5:14b", ModelProfile.PRODUCTION.model(ModelTier.LARGE),
                "production upgrades the heavy tier to 14B");
    }

    @Test
    void fromEnvironmentDefaultsToCpuOnlyDisabled() {
        // With no -Dagentkernel.* set, the resolved profile is cpu-only and disabled (abstain-by-default).
        ModelProfile env = ModelProfile.fromEnvironment();
        assertEquals("cpu-only", env.name());
        assertFalse(env.enabled(), "assist off unless explicitly enabled");
        assertFalse(OllamaModelProvider.fromEnvironment().anyAvailable());
    }
}
