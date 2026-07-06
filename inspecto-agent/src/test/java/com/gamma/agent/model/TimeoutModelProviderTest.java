package com.gamma.agent.model;

import com.gamma.agent.kernel.error.ModelError;
import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelTier;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hard-deadline enforcement (v4.1, B1): fast calls pass through, hung calls fail cleanly. */
class TimeoutModelProviderTest {

    private static final ModelRequest REQ = ModelRequest.text(ModelTier.SMALL, null, "ping");

    @Test
    void fastCallPassesThrough() {
        ModelProvider p = TimeoutModelProvider.wrap(FakeModelProvider.canned("pong"),
                Duration.ofSeconds(5));
        assertEquals("pong", p.generate(REQ).text());
        assertTrue(p.available());
    }

    @Test
    void hungCallFailsWithModelErrorAtTheDeadline() {
        ModelProvider hung = FakeModelProvider.responding(r -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "too late";
        });
        ModelProvider p = TimeoutModelProvider.wrap(hung, Duration.ofMillis(200));
        long start = System.nanoTime();
        ModelError e = assertThrows(ModelError.class, () -> p.generate(REQ));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(ms < 5_000, "must fail at the deadline, not the provider's pace (took " + ms + "ms)");
    }

    @Test
    void delegateModelErrorIsPreserved() {
        ModelProvider broken = FakeModelProvider.responding(r -> {
            throw new ModelError("provider exploded");
        });
        ModelProvider p = TimeoutModelProvider.wrap(broken, Duration.ofSeconds(5));
        ModelError e = assertThrows(ModelError.class, () -> p.generate(REQ));
        assertEquals("provider exploded", e.getMessage());
    }

    @Test
    void nonPositiveTimeoutMeansNoWrapping() {
        ModelProvider raw = FakeModelProvider.canned("x");
        assertSame(raw, TimeoutModelProvider.wrap(raw, Duration.ZERO));
    }
}
