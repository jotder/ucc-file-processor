package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-D2: SEQUENCE_GAP and FLOW_CONSERVATION_IMBALANCE domain events are each promoted to a managed
 * ALERT object (with de-duplication).
 */
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

    private static Event imbalance(String pipeline, String node, String kind, long in, long out) {
        return Event.builder(EventType.FLOW_CONSERVATION_IMBALANCE)
                .pipeline(pipeline)
                .message("flow '" + pipeline + "' node '" + node + "': " + in + " in, " + out + " out (" + kind + ")")
                .attr("node", node)
                .attr("kind", kind)
                .attr("recordsIn", in)
                .attr("recordsOut", out)
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
    void promotesAConservationImbalanceToAnAlertObject() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        EventObjectBridge bridge = new EventObjectBridge(objects);

        bridge.onEvent(imbalance("evt_rollup", "flt", "LOSS", 3, 2));

        List<OperationalObject> alerts = objects.active(ObjectType.ALERT, "evt_rollup");
        assertEquals(1, alerts.size());
        OperationalObject a = alerts.get(0);
        assertEquals(EventObjectBridge.IMBALANCE_RULE, a.attributes().get("rule"));
        assertEquals("flt", a.attributes().get("node"));
        assertEquals("LOSS", a.attributes().get("kind"));
        assertEquals("3", a.attributes().get("recordsIn"));
        assertEquals("2", a.attributes().get("recordsOut"));
        assertTrue(a.title().startsWith("Data loss"), a.title());
        assertTrue(a.title().contains("flt"), a.title());
    }

    @Test
    void anActiveImbalanceForTheSameNodeIsNotDuplicated() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        EventObjectBridge bridge = new EventObjectBridge(objects);

        bridge.onEvent(imbalance("evt_rollup", "flt", "LOSS", 3, 2));
        bridge.onEvent(imbalance("evt_rollup", "flt", "LOSS", 5, 4));   // same node re-reported (e.g. after a restart)
        assertEquals(1, objects.active(ObjectType.ALERT, "evt_rollup").size(), "same node ⇒ no clone");

        bridge.onEvent(imbalance("evt_rollup", "agg", "AMPLIFICATION", 2, 6));   // a different node ⇒ its own object
        assertEquals(2, objects.active(ObjectType.ALERT, "evt_rollup").size());
    }

    @Test
    void anImbalanceWithoutANodeIsIgnored() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        EventObjectBridge bridge = new EventObjectBridge(objects);

        bridge.onEvent(Event.builder(EventType.FLOW_CONSERVATION_IMBALANCE).pipeline("p").message("no node attr").build());
        assertTrue(objects.active(ObjectType.ALERT, "p").isEmpty());
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
