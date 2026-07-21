package com.gamma.job;

import com.gamma.notify.NotificationTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2 core DoD: a notification template's {@code {{payload.<path>}}} and a job's {@code $signal.<path>}
 * bind/{@code when:} guard resolve the <em>identical</em> nested value from the same fact, because both
 * ultimately walk the same {@code com.gamma.util.DottedPath} (event-signal-backbone-plan §4.4) — one
 * evaluator, addressed two ways.
 */
class SharedDottedPathGrammarTest {

    @Test
    void templateAndSignalBindResolveTheSameNestedFact() {
        // The "fact": a Signal's structured payload, nested one level (e.g. job.run.completed rowsOut stats).
        Map<String, Object> payload = Map.of("stats", Map.of("rowsOut", 15184));

        // R-TMPL: a notification template addresses it as {{payload.stats.rowsOut}}.
        Map<String, Object> templateCtx = Map.of("payload", payload);
        String rendered = NotificationTemplate.render("rows={{payload.stats.rowsOut}}", templateCtx);
        assertEquals("rows=15184", rendered);

        // R-CHAIN: a job's when: guard and $signal bind address the very same path over the payload map.
        assertTrue(WhenGuard.eval("$signal.stats.rowsOut == 15184", payload));

        var ctx = new ParameterResolver.Context("run-1", java.time.Instant.EPOCH, "cron",
                java.time.ZoneOffset.UTC, () -> java.util.Optional.empty(), (j, n) -> java.util.Optional.empty(), payload);
        assertEquals("15184", ParameterResolver.deduce("$signal.stats.rowsOut", ctx));
    }
}
