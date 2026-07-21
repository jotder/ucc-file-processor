package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-D2: a SEQUENCE_GAP domain event is promoted to a managed ALERT object (with de-duplication). */
class EventObjectBridgeTest {

    private static Event gap(String pipeline, String expected) {
        return Event.builder(EventType.SEQUENCE_GAP)
                .pipeline(pipeline)
                .message("Missing expected file in sequence: " + expected)
                .attr("expected", expected)
                .attr("sequence", "cdr_{yyyyMMddHH}.csv")
                .attr("unit", "HOURS")
                .build();
    }

    @Test
    void promotesAGapToAnAlertObjectCarryingTheExpectedKey() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        EventObjectBridge bridge = new EventObjectBridge(objects);

        bridge.onEvent(gap("gap_src", "cdr_2026061402.csv"));

        List<OperationalObject> alerts = objects.active(ObjectType.ALERT, "gap_src");
        assertEquals(1, alerts.size());
        OperationalObject a = alerts.get(0);
        assertEquals("cdr_2026061402.csv", a.attributes().get("expected"));
        assertEquals(EventObjectBridge.GAP_RULE, a.attributes().get("rule"));
        assertEquals("cdr_{yyyyMMddHH}.csv", a.attributes().get("sequence"));
        assertTrue(a.title().contains("cdr_2026061402.csv"));
    }

    @Test
    void anActiveGapForTheSameKeyIsNotDuplicated() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        EventObjectBridge bridge = new EventObjectBridge(objects);

        bridge.onEvent(gap("gap_src", "cdr_2026061402.csv"));
        bridge.onEvent(gap("gap_src", "cdr_2026061402.csv"));   // re-reported (e.g. after a restart)
        assertEquals(1, objects.active(ObjectType.ALERT, "gap_src").size(), "same key ⇒ no clone");

        bridge.onEvent(gap("gap_src", "cdr_2026061405.csv"));   // a different hole ⇒ its own object
        assertEquals(2, objects.active(ObjectType.ALERT, "gap_src").size());
    }

    @Test
    void ignoresNonGapEventsAndEventsWithoutAnExpectedKey() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        EventObjectBridge bridge = new EventObjectBridge(objects);

        bridge.onEvent(Event.builder(EventType.FILE_RECEIVED).pipeline("p").message("x").build());
        bridge.onEvent(Event.builder(EventType.SEQUENCE_GAP).pipeline("p").message("no attrs").build());
        assertTrue(objects.active(ObjectType.ALERT, "p").isEmpty());
    }

    @Test
    void wiresThroughEventLogAsASubscriber() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        EventObjectBridge bridge = new EventObjectBridge(objects);
        java.util.function.Consumer<Event> sub = bridge::onEvent;
        EventLog.global().addSubscriber(sub);
        try {
            EventLog.global().emit(gap("bus_src", "cdr_2026061407.csv"));
            List<OperationalObject> alerts = objects.active(ObjectType.ALERT, "bus_src");
            assertEquals(1, alerts.size(), "emitting a SEQUENCE_GAP creates the ALERT via the subscriber");
            assertEquals("cdr_2026061407.csv", alerts.get(0).attributes().get("expected"));
        } finally {
            EventLog.global().removeSubscriber(sub);
        }
    }
}
