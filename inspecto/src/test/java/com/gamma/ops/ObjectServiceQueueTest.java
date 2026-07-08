package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventQuery;
import com.gamma.event.EventType;
import com.gamma.event.InMemoryEventStore;
import com.gamma.ops.queue.Queue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INC-4 queue subsystem on {@link ObjectService}: queue routing (round-robin / least-loaded / manual),
 * assignment that both sets the assignee and advances the workflow, the watcher list, and the SLA
 * escalation policy applied by the sweep. Events are filtered by the fresh object id so the process-wide
 * log shared across tests can't pollute the assertions.
 */
class ObjectServiceQueueTest {

    private static List<Event> eventsFor(InMemoryEventStore store, String type, String objectId) {
        return store.query(EventQuery.builder().type(type).limit(10_000).build()).stream()
                .filter(e -> objectId.equals(e.attributes().get("objectId"))).toList();
    }

    private static OperationalObject incident(ObjectService svc, String title) {
        return svc.open(ObjectType.INCIDENT, title, "d", "HIGH", null, null, null, "corr", Map.of());
    }

    @Test
    void roundRobinCyclesMembersAndAssignAdvancesWorkflow() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerQueue(new Queue("triage", "Triage", "", List.of("alice", "bob"), Queue.Routing.ROUND_ROBIN));

        OperationalObject i1 = incident(svc, "one");
        OperationalObject a1 = svc.assign(i1.id(), null, "triage", "sys");
        assertEquals("alice", a1.assignee());
        assertEquals("ASSIGNED", a1.status(), "assign advances OPEN → ASSIGNED via the workflow");
        assertEquals(1, eventsFor(events, EventType.OBJECT_ASSIGNED, i1.id()).size());

        OperationalObject i2 = incident(svc, "two");
        assertEquals("bob", svc.assign(i2.id(), null, "triage", "sys").assignee(), "round-robin advances");
        OperationalObject i3 = incident(svc, "three");
        assertEquals("alice", svc.assign(i3.id(), null, "triage", "sys").assignee(), "and wraps");
    }

    @Test
    void leastLoadedPicksTheMemberWithFewestOpenObjects() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerQueue(new Queue("ops", "Ops", "", List.of("alice", "bob"), Queue.Routing.LEAST_LOADED));
        // Give alice two open incidents directly; bob has none.
        svc.assign(incident(svc, "a1").id(), "alice", null, null);
        svc.assign(incident(svc, "a2").id(), "alice", null, null);
        assertEquals("bob", svc.assign(incident(svc, "new").id(), null, "ops", null).assignee(),
                "least-loaded routes to the idle member");
    }

    @Test
    void manualQueueRequiresAnExplicitAssignee() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerQueue(new Queue("manual", "Manual", "", List.of("alice"), Queue.Routing.MANUAL));
        OperationalObject i = incident(svc, "x");
        assertThrows(IllegalStateException.class, () -> svc.assign(i.id(), null, "manual", null));
        assertEquals("alice", svc.assign(i.id(), "alice", "manual", null).assignee(),
                "an explicit assignee wins over manual routing");
    }

    @Test
    void assignGuards() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject i = incident(svc, "x");
        assertThrows(IllegalArgumentException.class, () -> svc.assign(i.id(), null, null, null),
                "neither assignee nor queue");
        assertThrows(java.util.NoSuchElementException.class, () -> svc.assign(i.id(), null, "ghost", null),
                "unknown queue");
        assertThrows(java.util.NoSuchElementException.class, () -> svc.assign("nope", "alice", null, null),
                "unknown object");
    }

    @Test
    void watchersAddRemoveIdempotent() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject i = incident(svc, "x");
        assertEquals(List.of(), i.watchers());
        assertEquals(List.of("alice"), svc.watch(i.id(), "alice").watchers());
        assertEquals(List.of("alice", "bob"), svc.watch(i.id(), "bob").watchers());
        assertEquals(List.of("alice", "bob"), svc.watch(i.id(), "alice").watchers(), "adding twice is idempotent");
        assertEquals(List.of("bob"), svc.unwatch(i.id(), "alice").watchers());
        assertEquals(List.of("bob"), svc.unwatch(i.id(), "alice").watchers(), "removing twice is idempotent");
    }

    @Test
    void slaSweepAppliesEscalationPolicy() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerQueue(new Queue("oncall", "On-call", "", List.of("carol"), Queue.Routing.ROUND_ROBIN));
        svc.escalationPolicy(new EscalationPolicy("CRITICAL", "oncall", true));

        long past = System.currentTimeMillis() - 60_000;
        OperationalObject i = svc.open(ObjectType.INCIDENT, "overdue", "d", "LOW", null, null, "alice", "corr",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(past)));

        assertEquals(1, svc.sweepIncidentSla(System.currentTimeMillis()), "one incident breaches");
        OperationalObject after = svc.get(i.id()).orElseThrow();
        assertEquals("CRITICAL", after.severity(), "escalation bumped severity");
        assertEquals("carol", after.assignee(), "escalation re-routed to the on-call queue");
        assertEquals(1, eventsFor(events, EventType.OBJECT_SLA_BREACH, i.id()).size());
        assertEquals(1, eventsFor(events, EventType.OBJECT_ESCALATED, i.id()).size());

        // Idempotent: a second sweep neither re-breaches nor re-escalates.
        assertEquals(0, svc.sweepIncidentSla(System.currentTimeMillis()));
        assertEquals(1, eventsFor(events, EventType.OBJECT_ESCALATED, i.id()).size());
    }

    @Test
    void slaSweepWithoutPolicyOnlyBreaches() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());   // no escalation policy installed
        long past = System.currentTimeMillis() - 60_000;
        OperationalObject i = svc.open(ObjectType.INCIDENT, "overdue", "d", "LOW", null, null, "alice", "corr",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(past)));

        assertEquals(1, svc.sweepIncidentSla(System.currentTimeMillis()));
        assertEquals("LOW", svc.get(i.id()).orElseThrow().severity(), "no policy → severity unchanged");
        assertEquals(1, eventsFor(events, EventType.OBJECT_SLA_BREACH, i.id()).size());
        assertEquals(0, eventsFor(events, EventType.OBJECT_ESCALATED, i.id()).size(), "no escalation without a policy");
    }
}
