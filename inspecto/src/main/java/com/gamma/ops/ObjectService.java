package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.ops.workflow.Workflow;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

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

    /** Attribute key holding an issue's SLA deadline as epoch millis (string) — set at creation (Phase 3). */
    public static final String ATTR_DUE_AT = "dueAt";
    /** Attribute key stamped (epoch millis) when an SLA breach has been emitted — makes {@link #sweepIssueSla} idempotent. */
    public static final String ATTR_SLA_BREACHED_AT = "slaBreachedAt";

    private final ObjectStore store;
    private final Map<ObjectType, Workflow> workflows = new EnumMap<>(ObjectType.class);

    /** Build with the built-in default workflows for every {@link ObjectType}. */
    public ObjectService(ObjectStore store) {
        this(store, Map.of());
    }

    /** Build with {@code overrides} taking precedence over the built-in {@link Workflow#defaultFor} set. */
    public ObjectService(ObjectStore store, Map<ObjectType, Workflow> overrides) {
        this.store = store;
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
     * {@code assignee} — the operator-set fields an issue is created with (Phase 3's {@code POST /objects}).
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
        EventLog.global().emit(Event.builder(EventType.OBJECT_OPENED)
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
     * SLA sweep (Phase 3): breach every {@link ObjectType#ISSUE} that has passed its {@link #ATTR_DUE_AT}
     * deadline while still being worked. An issue qualifies when it carries a {@code dueAt} attribute at
     * or before {@code now}, is not yet {@code RESOLVED} and not terminal ({@code CLOSED}), and has not
     * already breached. Each new breach stamps a {@link #ATTR_SLA_BREACHED_AT} marker (so repeated sweeps
     * never re-fire) and emits an {@link EventType#OBJECT_SLA_BREACH} event onto {@link EventLog#global()},
     * so the breach surfaces in the Event Viewer next to the issue's {@code OBJECT_ACTIVITY} history.
     *
     * <p>Intended to be driven by {@link com.gamma.service.Scheduler} (see {@code SourceService}); {@code now}
     * is injected so the schedule and tests evaluate against the same clock. Safe to call with no issues.
     *
     * @param now the wall-clock instant (epoch millis) to evaluate deadlines against
     * @return the number of issues newly breached by this sweep
     */
    public int sweepIssueSla(long now) {
        List<OperationalObject> issues = store.query(ObjectQuery.builder()
                .objectType(ObjectType.ISSUE).limit(ObjectQuery.MAX_LIMIT).build());
        int breached = 0;
        for (OperationalObject o : issues) {
            if (o.isClosed()) continue;                                      // terminal (CLOSED) — settled
            if ("RESOLVED".equalsIgnoreCase(o.status())) continue;           // fixed — SLA clock stopped
            if (o.attributes().containsKey(ATTR_SLA_BREACHED_AT)) continue;  // already breached — idempotent
            long dueAt = parseEpoch(o.attributes().get(ATTR_DUE_AT));
            if (dueAt <= 0 || dueAt > now) continue;                         // no SLA set, or not yet due
            OperationalObject marked = store.update(
                    o.withAttributes(Map.of(ATTR_SLA_BREACHED_AT, Long.toString(now)), now));
            EventLog.global().emit(Event.builder(EventType.OBJECT_SLA_BREACH)
                    .level(EventLevel.WARN)
                    .source(SOURCE)
                    .correlationId(marked.correlationId())
                    .message("ISSUE " + marked.id() + " breached SLA: due " + dueAt
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
        EventLog.global().emit(Event.builder(EventType.OBJECT_ACTIVITY)
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
