package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventQuery;
import com.gamma.event.EventType;
import com.gamma.event.InMemoryEventStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link ObjectService} orchestrator: opening an object in its initial state, walking the
 * workflow, the {@link EventType#OBJECT_OPENED}/{@link EventType#OBJECT_ACTIVITY} trail emitted onto
 * the shared {@link EventLog} (so the Event Viewer shows an object's history), illegal-move + unknown-id
 * guards, and the {@code active()} dedup helper. Events are filtered by the fresh object id so the
 * process-wide log shared across tests can't pollute the assertions.
 */
class ObjectServiceTest {

    private static List<Event> activityFor(InMemoryEventStore store, String type, String objectId) {
        return store.query(EventQuery.builder().type(type).limit(10_000).build()).stream()
                .filter(e -> objectId.equals(e.attributes().get("objectId"))).toList();
    }

    @Test
    void openWalkAndEmitActivity() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());

        OperationalObject open = svc.open(ObjectType.ALERT, "disk full", "msg", "CRITICAL", "pipeX",
                Map.of("rule", "r1"));
        assertEquals("OPEN", open.status());
        assertEquals(1, activityFor(events, EventType.OBJECT_OPENED, open.id()).size());

        OperationalObject acked = svc.ack(open.id(), "alice");
        assertEquals("ACKNOWLEDGED", acked.status());
        assertEquals(0, acked.closedAt());

        OperationalObject resolved = svc.resolve(open.id(), "bob");
        assertEquals("RESOLVED", resolved.status());
        assertTrue(resolved.isClosed(), "resolve is terminal → closedAt set");

        List<Event> activity = activityFor(events, EventType.OBJECT_ACTIVITY, open.id());
        assertEquals(2, activity.size(), "ack + resolve each recorded");
        assertEquals("RESOLVED", activity.get(0).attributes().get("to"), "newest-first: resolve");
        assertEquals("bob", activity.get(0).attributes().get("actor"));
        assertEquals("ack", activity.get(1).attributes().get("action"));
    }

    @Test
    void illegalTransitionRejected() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.ALERT, "t", "d", "INFO", null, Map.of());
        svc.resolve(o.id(), null);   // OPEN -> RESOLVED (terminal)
        assertThrows(IllegalStateException.class, () -> svc.ack(o.id(), null),
                "cannot ack a resolved alert");
    }

    @Test
    void unknownIdThrows() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        assertThrows(NoSuchElementException.class, () -> svc.transition("nope", "ack", null));
        assertThrows(NoSuchElementException.class, () -> svc.transitionTo("nope", "RESOLVED", null));
        assertTrue(svc.get("nope").isEmpty());
    }

    @Test
    void activeExcludesTerminalForDedup() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject a = svc.open(ObjectType.ALERT, "t", "d", "INFO", "pipe", Map.of("rule", "r"));
        assertEquals(1, svc.active(ObjectType.ALERT, "pipe").size());
        svc.resolve(a.id(), null);
        assertTrue(svc.active(ObjectType.ALERT, "pipe").isEmpty(), "resolved is no longer active");
    }

    @Test
    void transitionToValidatesNeighbour() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.ALERT, "t", "d", "INFO", null, Map.of());
        assertEquals("ACKNOWLEDGED", svc.transitionTo(o.id(), "ACKNOWLEDGED", "x").status());
        // RESOLVED is reachable from ACKNOWLEDGED; OPEN is not reachable from ACKNOWLEDGED.
        assertThrows(IllegalStateException.class, () -> svc.transitionTo(o.id(), "OPEN", "x"));
    }
}
