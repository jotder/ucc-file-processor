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

        NotificationRule rule = new NotificationRule("r1", "job.dataset.produced", EventLevel.INFO, "job",
                "{{type}} at {{time}}", "rows={{payload.rowsOut}} legacy={{attributes.rows}}", "{{type}}", true);
        Notification n = rule.render(e);

        assertEquals(e.timestamp(), n.title().substring(n.title().indexOf("at ") + 3),
                "{{time}} resolves to the event's ISO timestamp");
        assertEquals("rows=15184 legacy=1200", n.body(),
                "the structured payload and the legacy flat attributes are both addressable");
    }

    @Test
    void fromMapRequiresIdEventTypeAndCategoryAndDefaultsTheRest() {
        NotificationRule r = NotificationRule.fromMap(Map.of("id", "custom1", "eventType", "job.custom",
                "category", "ops"));
        assertEquals("custom1", r.id());
        assertEquals("job.custom", r.eventType());
        assertEquals("ops", r.category());
        assertNull(r.minLevel());
        assertTrue(r.enabled());
        assertEquals("{{type}}", r.titleTemplate());
        assertEquals("{{message}}", r.bodyTemplate());

        assertThrows(IllegalArgumentException.class,
                () -> NotificationRule.fromMap(Map.of("eventType", "job.custom", "category", "ops")));
        assertThrows(IllegalArgumentException.class, () -> NotificationRule.fromMap(
                Map.of("id", "c", "eventType", "job.custom", "category", "ops", "minLevel", "bogus")));
    }

    @Test
    void toMapRoundTripsThroughFromMap() {
        NotificationRule r = new NotificationRule("c1", "job.custom", EventLevel.WARN, "ops",
                "T", "B", "D", false);
        NotificationRule back = NotificationRule.fromMap(r.toMap());
        assertEquals(r, back);
    }
}
