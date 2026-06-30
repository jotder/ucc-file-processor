package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.ops.link.InMemoryLinkStore;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.ops.link.LinkStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.InMemoryNoteStore;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.NoteStore;
import com.gamma.ops.note.ObjectNote;
import com.gamma.ops.rca.RcaTemplate;
import com.gamma.ops.workflow.Workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * The Object Engine + Workflow Engine, wired together — the orchestrator behind the Alert Center
 * (Phase 2). It opens {@link OperationalObject}s in their workflow's initial state and walks them
 * through lifecycle transitions, persisting each change via an {@link ObjectStore} and recording it as
 * a Phase-1 {@link Event} ({@link EventType#OBJECT_OPENED} / {@link EventType#OBJECT_ACTIVITY}) on
 * {@link EventLog#global()} — so an investigator sees the object's history inline in the Event Viewer.
 *
 * <p>Lifecycle rules come from a {@link Workflow} per {@link ObjectType}: the built-in
 * {@link Workflow#defaultFor} set, optionally overridden (e.g. from {@code *_workflow.toon}). Illegal
 * moves throw {@link IllegalStateException}; an unknown id throws {@link NoSuchElementException} — the
 * Control API maps these to 422 / 404.
 *
 * @since 4.3.0
 */
@com.gamma.api.PublicApi(since = "4.3.0")
public final class ObjectService {

    private static final String SOURCE = ObjectService.class.getName();

    /** Attribute key holding an incident's SLA deadline as epoch millis (string) — set at creation (Phase 3). */
    public static final String ATTR_DUE_AT = "dueAt";
    /** Attribute key stamped (epoch millis) when an SLA breach has been emitted — makes {@link #sweepIncidentSla} idempotent. */
    public static final String ATTR_SLA_BREACHED_AT = "slaBreachedAt";

    private final ObjectStore store;
    private final LinkStore links;
    private final NoteStore notes;
    private final Map<ObjectType, Workflow> workflows = new EnumMap<>(ObjectType.class);

    /** Build with the built-in default workflows and in-memory link + note stores. */
    public ObjectService(ObjectStore store) {
        this(store, Map.of());
    }

    /** Build with workflow {@code overrides}; in-memory link + note stores. */
    public ObjectService(ObjectStore store, Map<ObjectType, Workflow> overrides) {
        this(store, overrides, new InMemoryLinkStore(), new InMemoryNoteStore());
    }

    /** Build with workflow {@code overrides} and an explicit {@link LinkStore}; in-memory note store. */
    public ObjectService(ObjectStore store, Map<ObjectType, Workflow> overrides, LinkStore links) {
        this(store, overrides, links, new InMemoryNoteStore());
    }

    /**
     * Build with workflow {@code overrides} and explicit {@link LinkStore} (Phase 4) + {@link NoteStore}
     * (Phase 4 follow-up) — the deployment supplies durable {@code Db*} stores or the lean in-memory ones,
     * mirroring the object store backend.
     */
    public ObjectService(ObjectStore store, Map<ObjectType, Workflow> overrides, LinkStore links, NoteStore notes) {
        this.store = store;
        this.links = links;
        this.notes = notes;
        for (ObjectType t : ObjectType.values()) {
            Workflow wf = overrides == null ? null : overrides.get(t);
            workflows.put(t, wf != null ? wf : Workflow.defaultFor(t));
        }
    }

    /** The effective workflow for {@code type}. */
    public Workflow workflow(ObjectType type) {
        return workflows.get(type);
    }

    /**
     * Open a new object in its workflow's initial state, persist it, and emit an
     * {@link EventType#OBJECT_OPENED} event. Convenience overload with no ownership/priority (used by
     * the auto-promoting {@link com.gamma.alert.AlertService}).
     */
    public OperationalObject open(ObjectType type, String title, String description, String severity,
                                  String correlationId, Map<String, String> attributes) {
        return open(type, title, description, severity, null, null, null, correlationId, attributes);
    }

    /**
     * Open a new object in its workflow's initial state, persist it, and emit an
     * {@link EventType#OBJECT_OPENED} event. The fuller form carries {@code priority}/{@code owner}/
     * {@code assignee} — the operator-set fields an incident is created with (Phase 3's {@code POST /objects}).
     */
    public OperationalObject open(ObjectType type, String title, String description, String severity,
                                  String priority, String owner, String assignee,
                                  String correlationId, Map<String, String> attributes) {
        long now = System.currentTimeMillis();
        OperationalObject obj = OperationalObject.builder(type)
                .title(title)
                .description(description)
                .severity(severity)
                .priority(priority)
                .owner(owner)
                .assignee(assignee)
                .status(workflow(type).initialState())
                .correlationId(correlationId)
                .attributes(attributes)
                .createdAt(now)
                .updatedAt(now)
                .build();
        OperationalObject stored = store.create(obj);
        EventLog.current().emit(Event.builder(EventType.OBJECT_OPENED)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(correlationId)
                .message(type + " opened: " + stored.title() + " [" + stored.id() + "]")
                .attr("objectId", stored.id())
                .attr("objectType", type.name())
                .attr("status", stored.status())
                .attr("severity", severity));
        return stored;
    }

    /**
     * Apply a named {@code action} to the object's current state (e.g. {@code ack}, {@code resolve}),
     * persist, and emit an {@link EventType#OBJECT_ACTIVITY} event.
     *
     * @throws NoSuchElementException if no object has this id
     * @throws IllegalStateException  if the action is not legal from the current state
     */
    public OperationalObject transition(String id, String action, String actor) {
        OperationalObject obj = require(id);
        Workflow wf = workflow(obj.objectType());
        String target = wf.apply(obj.status(), action).orElseThrow(() -> new IllegalStateException(
                "illegal transition: '" + action + "' from " + obj.status()
                        + " (" + obj.objectType() + ")"));
        return commit(obj, wf, target, action, actor);
    }

    /**
     * Move the object directly to {@code targetState} (must be a legal neighbour of the current state),
     * persist, and emit an {@link EventType#OBJECT_ACTIVITY} event.
     */
    public OperationalObject transitionTo(String id, String targetState, String actor) {
        OperationalObject obj = require(id);
        Workflow wf = workflow(obj.objectType());
        if (!wf.allows(obj.status(), targetState))
            throw new IllegalStateException("illegal transition: " + obj.status() + " -> " + targetState
                    + " (" + obj.objectType() + ")");
        return commit(obj, wf, targetState, "transition", actor);
    }

    /** Convenience: acknowledge an object (the {@code ack} action). */
    public OperationalObject ack(String id, String actor) {
        return transition(id, "ack", actor);
    }

    /** Convenience: resolve an object (the {@code resolve} action). */
    public OperationalObject resolve(String id, String actor) {
        return transition(id, "resolve", actor);
    }

    public Optional<OperationalObject> get(String id) {
        return store.get(id);
    }

    public List<OperationalObject> query(ObjectQuery query) {
        return store.query(query);
    }

    /**
     * The not-yet-terminal objects of {@code type} for a {@code correlationId} — used to avoid opening a
     * duplicate object while one is still being handled (e.g. an alert that keeps breaching).
     */
    public List<OperationalObject> active(ObjectType type, String correlationId) {
        Workflow wf = workflow(type);
        return store.query(ObjectQuery.builder()
                        .objectType(type).correlationId(correlationId).limit(ObjectQuery.MAX_LIMIT).build())
                .stream().filter(o -> !wf.isTerminal(o.status())).toList();
    }

    /**
     * SLA sweep (Phase 3): breach every {@link ObjectType#INCIDENT} that has passed its {@link #ATTR_DUE_AT}
     * deadline while still being worked. An incident qualifies when it carries a {@code dueAt} attribute at
     * or before {@code now}, is not yet {@code RESOLVED} and not terminal ({@code CLOSED}), and has not
     * already breached. Each new breach stamps a {@link #ATTR_SLA_BREACHED_AT} marker (so repeated sweeps
     * never re-fire) and emits an {@link EventType#OBJECT_SLA_BREACH} event onto {@link EventLog#global()},
     * so the breach surfaces in the Event Viewer next to the incident's {@code OBJECT_ACTIVITY} history.
     *
     * <p>Intended to be driven by {@link com.gamma.service.Scheduler} (see {@code SourceService}); {@code now}
     * is injected so the schedule and tests evaluate against the same clock. Safe to call with no incidents.
     *
     * @param now the wall-clock instant (epoch millis) to evaluate deadlines against
     * @return the number of incidents newly breached by this sweep
     */
    public int sweepIncidentSla(long now) {
        List<OperationalObject> incidents = store.query(ObjectQuery.builder()
                .objectType(ObjectType.INCIDENT).limit(ObjectQuery.MAX_LIMIT).build());
        int breached = 0;
        for (OperationalObject o : incidents) {
            if (o.isClosed()) continue;                                      // terminal (CLOSED) — settled
            if ("RESOLVED".equalsIgnoreCase(o.status())) continue;           // fixed — SLA clock stopped
            if (o.attributes().containsKey(ATTR_SLA_BREACHED_AT)) continue;  // already breached — idempotent
            long dueAt = parseEpoch(o.attributes().get(ATTR_DUE_AT));
            if (dueAt <= 0 || dueAt > now) continue;                         // no SLA set, or not yet due
            OperationalObject marked = store.update(
                    o.withAttributes(Map.of(ATTR_SLA_BREACHED_AT, Long.toString(now)), now));
            EventLog.current().emit(Event.builder(EventType.OBJECT_SLA_BREACH)
                    .level(EventLevel.WARN)
                    .source(SOURCE)
                    .correlationId(marked.correlationId())
                    .message("INCIDENT " + marked.id() + " breached SLA: due " + dueAt
                            + ", overdue " + (now - dueAt) + "ms")
                    .attr("objectId", marked.id())
                    .attr("objectType", marked.objectType().name())
                    .attr("status", marked.status())
                    .attr("severity", marked.severity())
                    .attr("assignee", marked.assignee())
                    .attr("dueAt", dueAt)
                    .attr("overdueMs", now - dueAt));
            breached++;
        }
        return breached;
    }

    // ── correlation graph (Phase 4) ──────────────────────────────────────────────────

    /**
     * Persist a directed correlation {@link ObjectLink} {@code from --relationship--> to} (e.g. a CASE
     * {@code CONTAINS} an INCIDENT) and emit an {@link EventType#OBJECT_LINKED} event so the correlation
     * shows in the Event Viewer. Both endpoints must exist (else {@link NoSuchElementException} → 404).
     * Idempotent: an identical edge (same {@code from}/{@code to}/{@code relationship}) is returned as-is
     * rather than duplicated. A {@code null} {@code relationship} defaults to {@link LinkRelationship#RELATED_TO}.
     */
    public ObjectLink link(String fromId, String toId, String relationship, String actor) {
        OperationalObject from = require(fromId);
        OperationalObject to = require(toId);
        String rel = (relationship == null || relationship.isBlank()) ? LinkRelationship.RELATED_TO : relationship;
        for (ObjectLink existing : links.incident(fromId)) {
            if (existing.fromId().equals(fromId) && existing.toId().equals(toId)
                    && existing.relationship().equalsIgnoreCase(rel))
                return existing;   // already linked — idempotent
        }
        ObjectLink created = links.add(ObjectLink.of(fromId, from.objectType(), toId, to.objectType(), rel));
        EventLog.current().emit(Event.builder(EventType.OBJECT_LINKED)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(from.correlationId())
                .message(from.objectType() + " " + fromId + " " + created.relationship() + " "
                        + to.objectType() + " " + toId + (actor == null ? "" : " (by " + actor + ")"))
                .attr("objectId", fromId)
                .attr("from", fromId)
                .attr("fromType", from.objectType().name())
                .attr("to", toId)
                .attr("toType", to.objectType().name())
                .attr("relationship", created.relationship())
                .attr("actor", actor));
        return created;
    }

    /** Every link touching {@code id} at either end, newest-first (the object's correlations). */
    public List<ObjectLink> linksOf(String id) {
        return links.incident(id);
    }

    /**
     * A correlation subgraph around {@code rootId} out to {@code depth} hops (BFS over links in both
     * directions): {@code {root, depth, nodes:[{id,objectType,title,status,severity}], edges:[link maps]}}.
     * {@code nodes} carries a light summary of each reachable object (skipping any whose row no longer
     * exists), so the UI can render the graph without extra lookups. Unknown root → {@link NoSuchElementException}.
     */
    public Map<String, Object> graph(String rootId, int depth) {
        require(rootId);
        int maxDepth = Math.max(1, depth);
        Set<String> seen = new LinkedHashSet<>();
        Set<ObjectLink> edges = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        seen.add(rootId);
        frontier.add(rootId);
        for (int hop = 0; hop < maxDepth && !frontier.isEmpty(); hop++) {
            for (int i = frontier.size(); i > 0; i--) {
                String cur = frontier.poll();
                for (ObjectLink l : links.incident(cur)) {
                    edges.add(l);
                    String other = l.other(cur);
                    if (other != null && seen.add(other)) frontier.add(other);
                }
            }
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String oid : seen) store.get(oid).ifPresent(o -> nodes.add(nodeSummary(o)));
        List<Map<String, Object>> edgeMaps = new ArrayList<>();
        for (ObjectLink l : edges) edgeMaps.add(l.toMap());
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("root", rootId);
        g.put("depth", maxDepth);
        g.put("nodes", nodes);
        g.put("edges", edgeMaps);
        return g;
    }

    private static Map<String, Object> nodeSummary(OperationalObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("objectType", o.objectType().name());
        m.put("title", o.title());
        m.put("status", o.status());
        m.put("severity", o.severity());
        return m;
    }

    // ── evidence: comments / attachments / RCA (Phase 4 follow-up) ───────────────────

    /** Add a free-text comment to an object (unknown id → {@link NoSuchElementException}); emits OBJECT_NOTE. */
    public ObjectNote comment(String objectId, String author, String body) {
        OperationalObject o = require(objectId);
        return addNote(ObjectNote.comment(objectId, author, body), author, o.correlationId());
    }

    /**
     * Attach a reference to external evidence (file/URL <em>metadata only</em> — the bytes stay out of the
     * lean core) to an object; emits an {@link EventType#OBJECT_NOTE} event. Unknown id → {@link NoSuchElementException}.
     */
    public ObjectNote attach(String objectId, String author, String name, String contentType,
                             String uri, String caption) {
        OperationalObject o = require(objectId);
        return addNote(ObjectNote.attachment(objectId, author, name, contentType, uri, caption), author,
                o.correlationId());
    }

    /** An object's notes, newest-first; {@code kind} {@code null} returns comments and attachments alike. */
    public List<ObjectNote> notesOf(String objectId, NoteKind kind) {
        return notes.forObject(objectId, kind);
    }

    /**
     * Apply an {@link RcaTemplate} to an object (typically a CASE): seed one {@link NoteKind#COMMENT} per
     * template section, giving the investigator a structured skeleton to complete. Unknown id →
     * {@link NoSuchElementException}. Returns the seeded notes in section order.
     */
    public List<ObjectNote> applyRca(String objectId, RcaTemplate template, String actor) {
        OperationalObject o = require(objectId);
        List<ObjectNote> seeded = new ArrayList<>();
        for (String section : template.sections())
            seeded.add(addNote(ObjectNote.comment(objectId, actor, "## " + section), actor, o.correlationId()));
        return seeded;
    }

    private ObjectNote addNote(ObjectNote note, String actor, String correlationId) {
        ObjectNote stored = notes.add(note);
        EventLog.current().emit(Event.builder(EventType.OBJECT_NOTE)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(correlationId)
                .message(stored.kind() + " on " + stored.objectId()
                        + (actor == null || actor.isBlank() ? "" : " by " + actor))
                .attr("objectId", stored.objectId())
                .attr("noteId", stored.id())
                .attr("noteKind", stored.kind().name())
                .attr("author", actor));
        return stored;
    }

    // ── internals ──────────────────────────────────────────────────────────────────

    private static long parseEpoch(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private OperationalObject require(String id) {
        return store.get(id).orElseThrow(() -> new NoSuchElementException("no object with id '" + id + "'"));
    }

    private OperationalObject commit(OperationalObject obj, Workflow wf, String target,
                                     String action, String actor) {
        long now = System.currentTimeMillis();
        OperationalObject next = obj.withStatus(target, now, wf.isTerminal(target));
        OperationalObject updated = store.update(next);
        EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(obj.correlationId())
                .message(obj.objectType() + " " + obj.id() + ": " + obj.status() + " -> " + target
                        + " (" + action + (actor == null ? "" : " by " + actor) + ")")
                .attr("objectId", obj.id())
                .attr("objectType", obj.objectType().name())
                .attr("from", obj.status())
                .attr("to", target)
                .attr("action", action)
                .attr("actor", actor));
        return updated;
    }
}
