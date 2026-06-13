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

    // ── Phase 3: ISSUE lifecycle + SLA sweep ────────────────────────────────────────

    @Test
    void issueLifecycleWalk() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.ISSUE, "bad rows", "investigate", "HIGH", "P1",
                null, "alice", "pipeC", Map.of());
        assertEquals("OPEN", o.status());
        assertEquals("P1", o.priority(), "the fuller open() carries priority");
        assertEquals("alice", o.assignee(), "the fuller open() carries assignee");

        assertEquals("ASSIGNED", svc.transition(o.id(), "assign", "alice").status());
        assertEquals("IN_PROGRESS", svc.transition(o.id(), "start", "alice").status());
        OperationalObject resolved = svc.transition(o.id(), "resolve", "alice");
        assertEquals("RESOLVED", resolved.status());
        assertEquals(0, resolved.closedAt(), "RESOLVED is not terminal for an ISSUE");
        OperationalObject closed = svc.transition(o.id(), "close", "bob");
        assertEquals("CLOSED", closed.status());
        assertTrue(closed.isClosed(), "CLOSED is terminal → closedAt set");
        assertThrows(IllegalStateException.class, () -> svc.transition(o.id(), "start", null),
                "cannot reopen a closed issue");
    }

    @Test
    void slaSweepBreachesOverdueUnresolvedIssues() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());

        long now = System.currentTimeMillis();
        OperationalObject overdue = svc.open(ObjectType.ISSUE, "overdue", "d", "HIGH", "pipeD",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 60_000)));
        OperationalObject future = svc.open(ObjectType.ISSUE, "not yet", "d", "LOW", "pipeE",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now + 3_600_000)));
        OperationalObject noSla = svc.open(ObjectType.ISSUE, "no sla", "d", "LOW", "pipeF", Map.of());

        assertEquals(1, svc.sweepIssueSla(now), "only the overdue issue breaches");
        assertEquals(1, activityFor(events, EventType.OBJECT_SLA_BREACH, overdue.id()).size());
        assertTrue(svc.get(overdue.id()).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
        assertFalse(svc.get(future.id()).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
        assertFalse(svc.get(noSla.id()).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));

        // idempotent: a second sweep at the same instant does not re-breach or re-emit
        assertEquals(0, svc.sweepIssueSla(now));
        assertEquals(1, activityFor(events, EventType.OBJECT_SLA_BREACH, overdue.id()).size());
    }

    @Test
    void slaSweepIgnoresResolvedAndClosedIssues() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        long now = System.currentTimeMillis();
        OperationalObject o = svc.open(ObjectType.ISSUE, "fixed in time", "d", "HIGH", "pipeG",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 60_000)));
        svc.transition(o.id(), "assign", "a");
        svc.transition(o.id(), "start", "a");
        svc.transition(o.id(), "resolve", "a");   // RESOLVED — the SLA clock has stopped
        assertEquals(0, svc.sweepIssueSla(now), "a resolved issue past its due time does not breach");
        svc.transition(o.id(), "close", "a");      // CLOSED — still no breach
        assertEquals(0, svc.sweepIssueSla(now));
    }
}
