package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventQuery;
import com.gamma.event.EventType;
import com.gamma.event.InMemoryEventStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.ObjectNote;
import com.gamma.ops.rca.RcaTemplate;
import com.gamma.util.JsonAttributes;
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

    // ── Phase 3: INCIDENT lifecycle + SLA sweep ────────────────────────────────────────

    /** A complete I1 postmortem blob (timeline + cause analysis + actions), paired with {@code ATTR_DUE_AT}. */
    private static Map<String, String> completePostmortemAttrs(long dueAt) {
        Map<String, Object> postmortem = Map.of(
                "timeline", List.of(Map.of("time", "10:00", "text", "detected")),
                "causeAnalysis", List.of("root cause found"),
                "actions", List.of(Map.of("done", false, "text", "patch job", "owner", "alice", "due", "")));
        return Map.of(
                "postmortem", JsonAttributes.toPayloadJson(postmortem),
                ObjectService.ATTR_DUE_AT, Long.toString(dueAt));
    }

    @Test
    void incidentLifecycleWalk() {
        // The mail lifecycle (GLOSSARY §9): IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED, with reopen.
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.INCIDENT, "bad rows", "investigate", "HIGH", "P1",
                null, "alice", "pipeC", completePostmortemAttrs(System.currentTimeMillis() + 3_600_000));
        assertEquals("IDENTIFIED", o.status());
        assertEquals("P1", o.priority(), "the fuller open() carries priority");
        assertEquals("alice", o.assignee(), "the fuller open() carries assignee");

        assertEquals("DIAGNOSING", svc.transition(o.id(), "accept", "alice").status());
        OperationalObject resolved = svc.transition(o.id(), "resolve", "alice");
        assertEquals("RESOLVED", resolved.status());
        assertEquals(0, resolved.closedAt(), "RESOLVED is not terminal for an INCIDENT");
        OperationalObject archived = svc.transition(o.id(), "archive", "bob");
        assertEquals("ARCHIVED", archived.status());
        assertTrue(archived.isClosed(), "ARCHIVED is terminal → closedAt set");
        OperationalObject reopened = svc.transition(o.id(), "reopen", "carol");
        assertEquals("DIAGNOSING", reopened.status());
        assertFalse(reopened.isClosed(), "reopen clears closedAt — the incident is live again");
        assertThrows(IllegalStateException.class, () -> svc.transition(o.id(), "accept", null),
                "accept is only legal from IDENTIFIED");
    }

    // ── I1: incident resolution hard gate (backend follow-up to the UI soft-warn) ─────

    @Test
    void resolveBlockedWithoutPostmortemSections() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.INCIDENT, "bad rows", "investigate", "HIGH", "P1",
                null, "alice", "pipeC", Map.of());
        svc.transition(o.id(), "accept", "alice");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.transition(o.id(), "resolve", "alice"),
                "no postmortem/dueAt set — resolution must be blocked");
        assertTrue(ex.getMessage().contains("timeline"), ex.getMessage());
        assertTrue(ex.getMessage().contains("cause analysis"), ex.getMessage());
        assertTrue(ex.getMessage().contains("corrective actions"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SLA"), ex.getMessage());
        assertEquals("DIAGNOSING", svc.get(o.id()).orElseThrow().status(), "status must not have changed");
    }

    @Test
    void resolveBlockedWithPartialPostmortem() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        // dueAt set, but no postmortem sections at all.
        OperationalObject o = svc.open(ObjectType.INCIDENT, "bad rows", "d", "HIGH", "pipeC",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(System.currentTimeMillis() + 3_600_000)));
        svc.transition(o.id(), "accept", null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.transition(o.id(), "resolve", null));
        assertFalse(ex.getMessage().contains("SLA"), "dueAt was set, so SLA gap must not be reported");
        assertTrue(ex.getMessage().contains("timeline"));
    }

    @Test
    void resolveAllowedOncePostmortemComplete() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.INCIDENT, "bad rows", "investigate", "HIGH", "P1",
                null, "alice", "pipeC", completePostmortemAttrs(System.currentTimeMillis() + 3_600_000));
        svc.transition(o.id(), "accept", "alice");
        assertEquals("RESOLVED", svc.transition(o.id(), "resolve", "alice").status());
    }

    @Test
    void resolutionGateOnlyAppliesToIncidents() {
        // ALERT and CASE also reach a RESOLVED status via "resolve" — the I1 gate is INCIDENT-only.
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject alert = svc.open(ObjectType.ALERT, "t", "d", "INFO", null, Map.of());
        assertEquals("RESOLVED", svc.resolve(alert.id(), null).status());
    }

    @Test
    void slaSweepBreachesOverdueUnresolvedIncidents() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());

        long now = System.currentTimeMillis();
        OperationalObject overdue = svc.open(ObjectType.INCIDENT, "overdue", "d", "HIGH", "pipeD",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 60_000)));
        OperationalObject future = svc.open(ObjectType.INCIDENT, "not yet", "d", "LOW", "pipeE",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now + 3_600_000)));
        OperationalObject noSla = svc.open(ObjectType.INCIDENT, "no sla", "d", "LOW", "pipeF", Map.of());

        assertEquals(1, svc.sweepIncidentSla(now), "only the overdue incident breaches");
        assertEquals(1, activityFor(events, EventType.OBJECT_SLA_BREACH, overdue.id()).size());
        assertTrue(svc.get(overdue.id()).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
        assertFalse(svc.get(future.id()).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
        assertFalse(svc.get(noSla.id()).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));

        // idempotent: a second sweep at the same instant does not re-breach or re-emit
        assertEquals(0, svc.sweepIncidentSla(now));
        assertEquals(1, activityFor(events, EventType.OBJECT_SLA_BREACH, overdue.id()).size());
    }

    @Test
    void slaSweepIgnoresResolvedAndClosedIncidents() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        long now = System.currentTimeMillis();
        OperationalObject o = svc.open(ObjectType.INCIDENT, "fixed in time", "d", "HIGH", "pipeG",
                completePostmortemAttrs(now - 60_000));
        svc.transition(o.id(), "accept", "a");
        svc.transition(o.id(), "resolve", "a");   // RESOLVED — the SLA clock has stopped
        assertEquals(0, svc.sweepIncidentSla(now), "a resolved incident past its due time does not breach");
        svc.transition(o.id(), "archive", "a");    // ARCHIVED — still no breach
        assertEquals(0, svc.sweepIncidentSla(now));
    }

    // ── Phase 4: correlation links + graph ──────────────────────────────────────────

    @Test
    void linkEmitsEventAndIsIdempotent() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject c = svc.open(ObjectType.CASE, "investigation", "d", "HIGH", "corr", Map.of());
        OperationalObject i = svc.open(ObjectType.INCIDENT, "bad rows", "d", "HIGH", "corr", Map.of());

        ObjectLink link = svc.link(c.id(), i.id(), "contains", "alice");
        assertEquals("CONTAINS", link.relationship());
        assertEquals(ObjectType.CASE, link.fromType());
        assertEquals(ObjectType.INCIDENT, link.toType());
        assertEquals(1, svc.linksOf(c.id()).size());
        assertEquals(1, svc.linksOf(i.id()).size(), "link is incident from both ends");
        assertEquals(1, activityFor(events, EventType.OBJECT_LINKED, c.id()).stream()
                .filter(e -> i.id().equals(e.attributes().get("to"))).count());

        // idempotent: re-linking the same edge returns the existing one, no duplicate
        svc.link(c.id(), i.id(), "CONTAINS", "bob");
        assertEquals(1, svc.linksOf(c.id()).size(), "duplicate edge not added");
    }

    @Test
    void linkRequiresBothEndpoints() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject c = svc.open(ObjectType.CASE, "case", "d", "HIGH", null, Map.of());
        assertThrows(NoSuchElementException.class, () -> svc.link(c.id(), "missing", "contains", null));
        assertThrows(NoSuchElementException.class, () -> svc.link("missing", c.id(), "contains", null));
    }

    @Test
    void graphTraversesToDepth() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject c = svc.open(ObjectType.CASE, "case", "d", "HIGH", null, Map.of());
        OperationalObject i = svc.open(ObjectType.INCIDENT, "incident", "d", "HIGH", null, Map.of());
        OperationalObject a = svc.open(ObjectType.ALERT, "alert", "d", "HIGH", null, Map.of());
        svc.link(c.id(), i.id(), "CONTAINS", null);       // CASE — INCIDENT
        svc.link(i.id(), a.id(), "ESCALATED_FROM", null); // INCIDENT — ALERT

        // depth 1 from the case reaches the incident (not the alert)
        Map<String, Object> g1 = svc.graph(c.id(), 1);
        assertEquals(2, ((List<?>) g1.get("nodes")).size());
        assertEquals(1, ((List<?>) g1.get("edges")).size());

        // depth 2 reaches the alert too
        Map<String, Object> g2 = svc.graph(c.id(), 2);
        assertEquals(3, ((List<?>) g2.get("nodes")).size());
        assertEquals(2, ((List<?>) g2.get("edges")).size());

        assertThrows(NoSuchElementException.class, () -> svc.graph("missing", 2));
    }

    // ── Phase 4 follow-up: comments / attachments / RCA ──────────────────────────────

    @Test
    void commentsAndAttachmentsRecordedAndQueryable() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject caseObj = svc.open(ObjectType.CASE, "investigation", "d", "HIGH", "corr", Map.of());

        ObjectNote c = svc.comment(caseObj.id(), "alice", "starting investigation");
        assertEquals(NoteKind.COMMENT, c.kind());
        ObjectNote a = svc.attach(caseObj.id(), "bob", "trace.log", "text/plain", "s3://x/trace.log", "tail -100");
        assertEquals(NoteKind.ATTACHMENT, a.kind());
        assertEquals("trace.log", a.attributes().get("name"));

        assertEquals(2, svc.notesOf(caseObj.id(), null).size());
        assertEquals(1, svc.notesOf(caseObj.id(), NoteKind.COMMENT).size());
        assertEquals(1, svc.notesOf(caseObj.id(), NoteKind.ATTACHMENT).size());
        assertEquals(2, activityFor(events, EventType.OBJECT_NOTE, caseObj.id()).size());

        assertThrows(NoSuchElementException.class, () -> svc.comment("missing", "x", "y"));
    }

    @Test
    void applyRcaSeedsOneCommentPerSection() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject caseObj = svc.open(ObjectType.CASE, "investigation", "d", "HIGH", null, Map.of());
        RcaTemplate template = RcaTemplate.fromMap(Map.of("name", "incident",
                "sections", List.of("Summary", "Root cause", "Remediation")));

        List<ObjectNote> seeded = svc.applyRca(caseObj.id(), template, "alice");
        assertEquals(3, seeded.size());
        assertEquals("## Summary", seeded.get(0).body(), "section order preserved in the returned list");
        assertEquals(3, svc.notesOf(caseObj.id(), NoteKind.COMMENT).size());
        assertThrows(NoSuchElementException.class, () -> svc.applyRca("missing", template, "x"));
    }
}
