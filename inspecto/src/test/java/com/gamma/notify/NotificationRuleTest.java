package com.gamma.notify;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link NotificationRule#context(Event)} — the {@code {{time}}} fix and the additive structured
 *  {@code payload} exposure (event-signal-backbone-plan §4.5.1). */
class NotificationRuleTest {

    @Test
    void timeAndPayloadAreExposedAlongsideTheLegacyAttributes() {
        Event e = Event.builder("job.dataset.produced")
                .ts(1_752_000_000_000L)
                .pipeline("orders")
                .message("dataset produced")
                .attr("rows", "1200")
                .payload(Map.of("rowsOut", 15184))
                .build();

        NotificationRule rule = new NotificationRule("job.dataset.produced", EventLevel.INFO, "job",
                "{{type}} at {{time}}", "rows={{payload.rowsOut}} legacy={{attributes.rows}}", "{{type}}");
        Notification n = rule.render(e);

        assertEquals(e.timestamp(), n.title().substring(n.title().indexOf("at ") + 3),
                "{{time}} resolves to the event's ISO timestamp");
        assertEquals("rows=15184 legacy=1200", n.body(),
                "the structured payload and the legacy flat attributes are both addressable");
    }
}
