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
import com.gamma.ops.queue.InMemoryQueueStore;
import com.gamma.ops.queue.Queue;
import com.gamma.ops.queue.QueueRouter;
import com.gamma.ops.queue.QueueStore;
import com.gamma.ops.rca.RcaTemplate;
import com.gamma.ops.tag.CaseRule;
import com.gamma.ops.tag.Tag;
import com.gamma.ops.tag.TagRule;
import com.gamma.ops.workflow.Workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /** Attribute key holding an object's comma-separated watcher list (INC-4). */
    public static final String ATTR_WATCHERS = "watchers";
    /** Attribute key holding an object's comma-separated tag list (GLOSSARY §9 — Tag / Tag Rule). */
    public static final String ATTR_TAGS = "tags";
    /** Attribute key stamped on an absorbed case: the surviving case it was merged into (GLOSSARY §9). */
    public static final String ATTR_MERGED_INTO = "mergedInto";
    /** Attribute key stamped on a rule-raised case: the {@link CaseRule} name that opened it (GLOSSARY §9, C5). */
    public static final String ATTR_RAISED_BY_RULE = "raisedByRule";

    private final ObjectStore store;
    private final LinkStore links;
    private final NoteStore notes;
    private final QueueStore queues = new InMemoryQueueStore();     // INC-4: work queues (config-authored)
    private volatile EscalationPolicy escalationPolicy;             // INC-4: applied on SLA breach; null = breach-only
    private final Map<ObjectType, Workflow> workflows = new EnumMap<>(ObjectType.class);
    private final Map<String, Tag> tags = new ConcurrentHashMap<>();          // user-created tag registry
    private final Map<String, TagRule> tagRules = new ConcurrentHashMap<>();  // Gmail-filter Tag Rules, by name
    private final Map<String, CaseRule> caseRules = new ConcurrentHashMap<>(); // rule-raised-case rules, by name (C5)

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
        obj = autoApplyTagRules(obj, now);   // Tag Rules tag incoming objects (GLOSSARY §9, Gmail-filter semantics)
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

    /**
     * Patch the operator-mutable fields ({@code PATCH /objects/{id}}): any non-null argument is applied —
     * {@code priority}/{@code severity}/{@code assignee} replace, {@code attributes} merge over the stored
     * bag (updates win, existing keys survive). No workflow involvement; a no-op patch returns the object
     * unchanged without a write.
     *
     * @throws NoSuchElementException if no object has this id
     */
    public OperationalObject patch(String id, String priority, String severity, String assignee,
                                   Map<String, String> attributes) {
        OperationalObject obj = require(id);
        long now = System.currentTimeMillis();
        OperationalObject next = obj;
        if (priority != null) next = next.withPriority(priority, now);
        if (severity != null) next = next.withSeverity(severity, now);
        if (assignee != null) next = next.withAssignee(assignee, now);
        if (attributes != null && !attributes.isEmpty()) next = next.withAttributes(attributes, now);
        return next == obj ? obj : store.update(next);
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

    // ── analytics (GLOSSARY §9, C4) ───────────────────────────────────────────────────

    /**
     * A rollup over all objects of {@code type} (C4 — the business-lens numbers): totals, backlog
     * (non-terminal count), breakdowns by status / L1-category / priority, cycle-time stats over the
     * terminal objects ({@code closedAt − createdAt}), and impact totals summed from the flat
     * {@code impactAmount} / {@code recordsAffected} attributes (the queryable columns the Findings
     * form writes alongside its JSON blob). Shaped as a JSON-ready map so the UI renders it directly
     * and a later Studio-dataset binding can read the same surface.
     */
    public Map<String, Object> analytics(ObjectType type) {
        Workflow wf = workflow(type);
        List<OperationalObject> all = store.query(ObjectQuery.builder()
                .objectType(type).limit(ObjectQuery.MAX_LIMIT).build());
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        int backlog = 0;
        long cycleSum = 0;
        int cycleCount = 0;
        double impactAmount = 0;
        long recordsAffected = 0;
        for (OperationalObject o : all) {
            bump(byStatus, o.status() == null ? "UNKNOWN" : o.status().toUpperCase(java.util.Locale.ROOT));
            bump(byCategory, categoryL1(o.attributes().get("category")));
            bump(byPriority, o.priority() == null || o.priority().isBlank() ? "NONE" : o.priority().toUpperCase(java.util.Locale.ROOT));
            if (!wf.isTerminal(o.status())) backlog++;
            if (o.closedAt() > 0 && o.closedAt() >= o.createdAt()) {
                cycleSum += o.closedAt() - o.createdAt();
                cycleCount++;
            }
            impactAmount += parseDoubleOr(o.attributes().get("impactAmount"), 0);
            recordsAffected += parseEpoch(o.attributes().get("recordsAffected")); // long-or-0 parse
        }
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("count", cycleCount);
        cycle.put("avgMs", cycleCount == 0 ? 0 : cycleSum / cycleCount);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("impactAmount", impactAmount);
        impact.put("recordsAffected", recordsAffected);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type.name());
        out.put("total", all.size());
        out.put("backlog", backlog);
        out.put("byStatus", byStatus);
        out.put("byCategory", byCategory);
        out.put("byPriority", byPriority);
        out.put("cycleTime", cycle);
        out.put("impact", impact);
        return out;
    }

    private static void bump(Map<String, Integer> m, String key) {
        m.merge(key, 1, Integer::sum);
    }

    /** The first level of a "L1 / L2 / L3" category path, or "UNCATEGORIZED". */
    private static String categoryL1(String category) {
        if (category == null || category.isBlank()) return "UNCATEGORIZED";
        int slash = category.indexOf('/');
        return (slash < 0 ? category : category.substring(0, slash)).trim();
    }

    private static double parseDoubleOr(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ── queues, assignment, watchers (INC-4) ─────────────────────────────────────────

    /** Register (create or replace) a work {@link Queue}; loaded from {@code *_queue.toon} at boot or {@code POST /queues}. */
    public Queue registerQueue(Queue queue) {
        return queues.put(queue);
    }

    /** The queue with this id, or empty. */
    public Optional<Queue> queue(String id) {
        return queues.get(id);
    }

    /** Every registered queue. */
    public List<Queue> queues() {
        return queues.all();
    }

    // ── tags & Tag Rules (GLOSSARY §9) ────────────────────────────────────────────────

    /** Register (create or replace) a {@link Tag}; loaded from {@code *_tag.toon} at boot or {@code POST /tags}. */
    public Tag registerTag(Tag tag) {
        tags.put(tag.name(), tag);
        return tag;
    }

    /** The registered tag with this name, or empty. */
    public Optional<Tag> tag(String name) {
        return Optional.ofNullable(name == null ? null : tags.get(name.trim()));
    }

    /** Every registered tag, sorted by name. */
    public List<Tag> tags() {
        return tags.values().stream().sorted(Comparator.comparing(Tag::name)).toList();
    }

    /**
     * Register (create or replace) a {@link TagRule}; loaded from {@code *_tagrule.toon} at boot or
     * {@code POST /tags/rules}. Saving a rule implicitly registers its tag (Gmail creates the label
     * with the filter).
     */
    public TagRule registerTagRule(TagRule rule) {
        tags.computeIfAbsent(rule.tag(), n -> new Tag(n, System.currentTimeMillis()));
        tagRules.put(rule.name(), rule);
        return rule;
    }

    /** The Tag Rule with this name, or empty. */
    public Optional<TagRule> tagRule(String name) {
        return Optional.ofNullable(name == null ? null : tagRules.get(name.trim()));
    }

    /** Every registered Tag Rule, sorted by name. */
    public List<TagRule> tagRules() {
        return tagRules.values().stream().sorted(Comparator.comparing(TagRule::name)).toList();
    }

    /** Remove a Tag Rule; {@code false} when no rule had that name. */
    public boolean removeTagRule(String name) {
        return name != null && tagRules.remove(name.trim()) != null;
    }

    /** Bulk-apply outcome: how many objects matched the rule, and how many were newly tagged. */
    public record TagRuleApplication(int matched, int updated) {}

    /**
     * Apply a saved Tag Rule to every existing match ({@code POST /tags/rules/{name}/apply}) — the
     * Gmail "also apply to existing" semantics. Idempotent: objects already carrying the tag count as
     * matched but are not rewritten.
     *
     * @throws NoSuchElementException if no rule has this name
     */
    public TagRuleApplication applyTagRule(String name) {
        TagRule rule = tagRule(name).orElseThrow(() -> new NoSuchElementException("no tag rule named '" + name + "'"));
        int matched = 0;
        int updated = 0;
        for (OperationalObject o : store.query(ObjectQuery.builder().limit(ObjectQuery.MAX_LIMIT).build())) {
            if (!rule.matches(o)) continue;
            matched++;
            List<String> current = csvTags(o.attributes().get(ATTR_TAGS));
            if (current.contains(rule.tag())) continue;
            current.add(rule.tag());
            store.update(o.withAttributes(Map.of(ATTR_TAGS, String.join(",", current)), System.currentTimeMillis()));
            updated++;
        }
        return new TagRuleApplication(matched, updated);
    }

    /** Merge every matching Tag Rule's tag into a not-yet-stored object's {@link #ATTR_TAGS} CSV. */
    private OperationalObject autoApplyTagRules(OperationalObject obj, long now) {
        if (tagRules.isEmpty()) return obj;
        List<String> merged = csvTags(obj.attributes().get(ATTR_TAGS));
        boolean changed = false;
        for (TagRule rule : tagRules.values()) {
            if (!merged.contains(rule.tag()) && rule.matches(obj)) {
                merged.add(rule.tag());
                changed = true;
            }
        }
        return changed ? obj.withAttributes(Map.of(ATTR_TAGS, String.join(",", merged)), now) : obj;
    }

    /** The comma-separated tag attribute parsed into a mutable, trimmed, non-empty list. */
    private static List<String> csvTags(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ── rule-raised cases (GLOSSARY §9, C5) ───────────────────────────────────────────

    /** Register (create or replace) a {@link CaseRule}; loaded from {@code *_caserule.toon} at boot or {@code POST /cases/rules}. */
    public CaseRule registerCaseRule(CaseRule rule) {
        caseRules.put(rule.name(), rule);
        return rule;
    }

    /** The Case Rule with this name, or empty. */
    public Optional<CaseRule> caseRule(String name) {
        return Optional.ofNullable(name == null ? null : caseRules.get(name.trim()));
    }

    /** Every registered Case Rule, sorted by name. */
    public List<CaseRule> caseRules() {
        return caseRules.values().stream().sorted(Comparator.comparing(CaseRule::name)).toList();
    }

    /** Remove a Case Rule; {@code false} when no rule had that name. */
    public boolean removeCaseRule(String name) {
        return name != null && caseRules.remove(name.trim()) != null;
    }

    /** Evaluate outcome: matching in-window incidents, how many were newly grouped, and the target case. */
    public record CaseRuleEvaluation(int matched, int grouped, String caseId, boolean opened) {}

    /**
     * Evaluate a Case Rule ({@code POST /cases/rules/{name}/evaluate}) — the auto-grouping step of the
     * Alert → Incident → Case chain (C5). Finds Incidents that match the rule's {@link TagRule.Filter},
     * were created within {@link CaseRule#windowMinutes} of {@code now}, and are not already a member of
     * any Case. If a still-open Case previously raised by this rule exists, the matches are attached to
     * it; otherwise, once at least {@link CaseRule#threshold} matches accrue, a new Case is opened
     * (inheriting the rule's title / category / tags) and the matches are grouped under it. Idempotent:
     * already-grouped incidents are skipped, so re-evaluation only attaches new ones.
     *
     * @throws NoSuchElementException if no rule has this name
     */
    public CaseRuleEvaluation evaluateCaseRule(String name) {
        CaseRule rule = caseRule(name).orElseThrow(() -> new NoSuchElementException("no case rule named '" + name + "'"));
        long now = System.currentTimeMillis();
        long cutoff = rule.windowMinutes() <= 0 ? 0 : now - rule.windowMinutes() * 60_000L;
        List<OperationalObject> matches = store.query(ObjectQuery.builder()
                        .objectType(ObjectType.INCIDENT).limit(ObjectQuery.MAX_LIMIT).build())
                .stream()
                .filter(o -> o.createdAt() >= cutoff)
                .filter(rule::matches)
                .filter(o -> !isCaseMember(o.id()))   // not already grouped under any case
                .toList();
        if (matches.isEmpty()) return new CaseRuleEvaluation(0, 0, null, false);

        String existing = openCaseRaisedBy(name);
        boolean opened = false;
        String caseId = existing;
        if (caseId == null) {
            if (matches.size() < rule.threshold())
                return new CaseRuleEvaluation(matches.size(), 0, null, false);  // below threshold, no case yet
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put(ATTR_RAISED_BY_RULE, name);
            if (rule.category() != null) attrs.put("category", rule.category());
            if (rule.tags() != null) attrs.put(ATTR_TAGS, rule.tags());
            caseId = open(ObjectType.CASE, rule.title(), "Auto-raised by case rule '" + name + "'",
                    null, null, null, null, matches.get(0).correlationId(), attrs).id();
            opened = true;
        }
        for (OperationalObject inc : matches) link(caseId, inc.id(), LinkRelationship.CONTAINS, "case-rule:" + name);
        return new CaseRuleEvaluation(matches.size(), matches.size(), caseId, opened);
    }

    /** Whether {@code incidentId} is already a {@code CONTAINS} member of some case. */
    private boolean isCaseMember(String incidentId) {
        return links.incident(incidentId).stream()
                .anyMatch(l -> l.toId().equals(incidentId) && LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship()));
    }

    /** The id of a still-open case previously raised by {@code ruleName}, or null. */
    private String openCaseRaisedBy(String ruleName) {
        return store.query(ObjectQuery.builder().objectType(ObjectType.CASE).limit(ObjectQuery.MAX_LIMIT).build())
                .stream()
                .filter(c -> ruleName.equals(c.attributes().get(ATTR_RAISED_BY_RULE)))
                .filter(c -> !workflow(ObjectType.CASE).isTerminal(c.status()))
                .map(OperationalObject::id)
                .findFirst().orElse(null);
    }

    /** Install the SLA {@link EscalationPolicy} the sweep applies on breach; {@code null} = breach-event only. */
    public void escalationPolicy(EscalationPolicy policy) {
        this.escalationPolicy = policy;
    }

    /** The installed escalation policy, or empty. */
    public Optional<EscalationPolicy> escalationPolicy() {
        return Optional.ofNullable(escalationPolicy);
    }

    /**
     * Assign an object to a person or route it through a queue (INC-4). Exactly one of {@code assignee} /
     * {@code queueId} drives the target: an explicit {@code assignee} wins; otherwise {@link QueueRouter}
     * picks a member of {@code queueId} per its {@link Queue.Routing}. Sets the assignee, records an
     * {@link EventType#OBJECT_ASSIGNED} event (the assignment history), and — when the current state has a
     * legal {@code assign} action (e.g. INCIDENT {@code OPEN → ASSIGNED}) — also advances the workflow.
     *
     * @throws NoSuchElementException  unknown object or queue id
     * @throws IllegalArgumentException neither an assignee nor a queue was supplied
     * @throws IllegalStateException    the queue can't yield an assignee (empty, or MANUAL with no explicit assignee)
     */
    public OperationalObject assign(String id, String assignee, String queueId, String actor) {
        OperationalObject obj = require(id);
        String target = resolveAssignee(assignee, queueId);
        long now = System.currentTimeMillis();
        String from = obj.assignee();
        OperationalObject updated = store.update(obj.withAssignee(target, now));
        EventLog.current().emit(Event.builder(EventType.OBJECT_ASSIGNED)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(obj.correlationId())
                .message(obj.objectType() + " " + id + " assigned to " + target
                        + (queueId != null ? " via queue " + queueId : "")
                        + (from == null || from.isBlank() ? "" : " (was " + from + ")")
                        + (actor == null ? "" : " by " + actor))
                .attr("objectId", id)
                .attr("objectType", obj.objectType().name())
                .attr("from", from)
                .attr("to", target)
                .attr("queue", queueId)
                .attr("actor", actor));
        // Unify assignment with the workflow: if the current state legally accepts an `assign` action
        // (INCIDENT OPEN → ASSIGNED), advance it too so status tracks reality. Absent such a transition
        // (already ASSIGNED, or a type without one) the assignee change alone stands.
        Workflow wf = workflow(obj.objectType());
        if (wf.apply(updated.status(), "assign").isPresent())
            return commit(updated, wf, wf.apply(updated.status(), "assign").get(), "assign", actor);
        return updated;
    }

    /** Resolve the concrete assignee: explicit name wins, else route through the queue. */
    private String resolveAssignee(String assignee, String queueId) {
        if (assignee != null && !assignee.isBlank()) return assignee.trim();
        if (queueId == null || queueId.isBlank())
            throw new IllegalArgumentException("assign needs an 'assignee' or a 'queue'");
        Queue q = queues.get(queueId).orElseThrow(() -> new NoSuchElementException("no queue with id '" + queueId + "'"));
        return QueueRouter.pick(q, queues, this::openLoadOf).orElseThrow(() -> new IllegalStateException(
                "queue '" + queueId + "' cannot pick an assignee (empty members, or manual routing needs an explicit assignee)"));
    }

    /** Open (non-closed) objects currently assigned to {@code member} — the load metric for least-loaded routing. */
    private int openLoadOf(String member) {
        return (int) store.query(ObjectQuery.builder().assignee(member).limit(ObjectQuery.MAX_LIMIT).build())
                .stream().filter(o -> !o.isClosed()).count();
    }

    /** Add {@code user} to an object's watcher list (idempotent); returns the updated object. Unknown id → 404. */
    public OperationalObject watch(String id, String user) {
        return mutateWatchers(id, user, true);
    }

    /** Remove {@code user} from an object's watcher list (idempotent); returns the updated object. Unknown id → 404. */
    public OperationalObject unwatch(String id, String user) {
        return mutateWatchers(id, user, false);
    }

    private OperationalObject mutateWatchers(String id, String user, boolean add) {
        if (user == null || user.isBlank()) throw new IllegalArgumentException("watch needs a 'user'");
        OperationalObject obj = require(id);
        String u = user.trim();
        List<String> current = new ArrayList<>(obj.watchers());
        boolean changed = add ? (!current.contains(u) && current.add(u)) : current.remove(u);
        if (!changed) return obj;   // idempotent — no write, no event
        return store.update(obj.withAttributes(
                Map.of(ATTR_WATCHERS, String.join(",", current)), System.currentTimeMillis()));
    }

    /**
     * SLA sweep (Phase 3): breach every {@link ObjectType#INCIDENT} that has passed its {@link #ATTR_DUE_AT}
     * deadline while still being worked. An incident qualifies when it carries a {@code dueAt} attribute at
     * or before {@code now}, is not yet {@code RESOLVED} and not terminal ({@code CLOSED}), and has not
     * already breached. Each new breach stamps a {@link #ATTR_SLA_BREACHED_AT} marker (so repeated sweeps
     * never re-fire) and emits an {@link EventType#OBJECT_SLA_BREACH} event onto {@link EventLog#global()},
     * so the breach surfaces in the Event Viewer next to the incident's {@code OBJECT_ACTIVITY} history.
     *
     * <p>Intended to be driven by {@link com.gamma.service.Scheduler} (see {@code CollectorService}); {@code now}
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
            escalate(marked, now);   // INC-4: apply the escalation policy (no-op when none is installed)
            breached++;
        }
        return breached;
    }

    /**
     * Apply the installed {@link EscalationPolicy} to a just-breached incident (INC-4): bump severity and/or
     * re-route to a queue, then emit an {@link EventType#OBJECT_ESCALATED} event so the notify chain re-alerts.
     * A no-op when no policy is installed (the sweep then behaves exactly as before — breach event only).
     */
    private void escalate(OperationalObject breached, long now) {
        EscalationPolicy policy = this.escalationPolicy;
        if (policy == null) return;
        OperationalObject obj = breached;
        String newSeverity = obj.severity();
        if (policy.severity() != null && !policy.severity().isBlank()) {
            newSeverity = policy.severity().trim();
            obj = obj.withSeverity(newSeverity, now);
        }
        String newAssignee = obj.assignee();
        String queueId = policy.reassignQueue();
        if (queueId != null && !queueId.isBlank()) {
            Optional<Queue> q = queues.get(queueId);
            if (q.isPresent()) {
                Optional<String> picked = QueueRouter.pick(q.get(), queues, this::openLoadOf);
                if (picked.isPresent()) { newAssignee = picked.get(); obj = obj.withAssignee(newAssignee, now); }
            } else {
                log.warn("escalation policy names unknown queue '{}' — skipping reassignment of {}", queueId, obj.id());
            }
        }
        if (policy.mutates()) store.update(obj);   // one persist for severity + assignee
        if (policy.renotify() || policy.mutates())
            EventLog.current().emit(Event.builder(EventType.OBJECT_ESCALATED)
                    .level(EventLevel.WARN)
                    .source(SOURCE)
                    .correlationId(obj.correlationId())
                    .message("INCIDENT " + obj.id() + " escalated after SLA breach"
                            + (policy.severity() != null ? " → severity " + newSeverity : "")
                            + (queueId != null ? " → queue " + queueId + " (" + newAssignee + ")" : ""))
                    .attr("objectId", obj.id())
                    .attr("objectType", obj.objectType().name())
                    .attr("severity", newSeverity)
                    .attr("queue", queueId)
                    .attr("assignee", newAssignee));
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ObjectService.class);

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
     * Remove the edge {@code fromId → toId} via {@code relationship} (case group management: e.g.
     * removing a member incident from a Case). The removal is audited as an {@link EventType#OBJECT_ACTIVITY}
     * event; {@code false} when no such edge exists. Unknown {@code fromId} → {@link NoSuchElementException}.
     */
    public boolean unlink(String fromId, String toId, String relationship, String actor) {
        OperationalObject from = require(fromId);
        String rel = (relationship == null || relationship.isBlank()) ? LinkRelationship.RELATED_TO : relationship;
        boolean removed = links.remove(fromId, toId, rel);
        if (removed) {
            EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                    .level(EventLevel.INFO)
                    .source(SOURCE)
                    .correlationId(from.correlationId())
                    .message(from.objectType() + " " + fromId + " unlinked " + rel.toUpperCase() + " " + toId
                            + (actor == null ? "" : " (by " + actor + ")"))
                    .attr("objectId", fromId)
                    .attr("from", fromId)
                    .attr("to", toId)
                    .attr("relationship", rel.toUpperCase())
                    .attr("action", "unlink")
                    .attr("actor", actor));
        }
        return removed;
    }

    // ── case group management: Split & Merge (GLOSSARY §9) ───────────────────────────

    /** Merge outcome: the updated survivor, the absorbed case ids, and how many member links moved. */
    public record MergeResult(OperationalObject survivor, List<String> merged, int membersMoved) {}

    /** Split outcome: the newly opened case and how many member links moved to it. */
    public record SplitResult(OperationalObject part, int membersMoved) {}

    /**
     * <b>Merge</b> {@code sources} into the surviving case {@code survivorId} — likely-similar cases
     * become one group managed as one. Per source: its {@code CONTAINS} members are re-pointed to the
     * survivor (idempotent), tags + watchers union onto the survivor, a {@code MERGED_INTO} trace link
     * + {@link #ATTR_MERGED_INTO} marker + comments record the merge, and the source is closed
     * (direct terminal move — an administrative action, deliberately outside the workflow). History,
     * comments and attachments stay on the source, reachable via the trace link.
     *
     * @throws NoSuchElementException   unknown survivor/source id
     * @throws IllegalArgumentException empty {@code sources}
     * @throws IllegalStateException    a non-CASE participant, self-merge, or an already-closed/merged source
     */
    public MergeResult mergeCases(String survivorId, List<String> sources, String actor) {
        if (sources == null || sources.isEmpty())
            throw new IllegalArgumentException("merge needs at least one source case");
        OperationalObject survivor = requireActiveCase(survivorId, "merge survivor");
        List<OperationalObject> absorbed = new ArrayList<>();
        for (String id : new LinkedHashSet<>(sources)) {
            if (id.equals(survivorId)) throw new IllegalStateException("a case cannot be merged into itself");
            absorbed.add(requireActiveCase(id, "merge source"));
        }
        long now = System.currentTimeMillis();
        int moved = 0;
        Set<String> tags = new LinkedHashSet<>(csvTags(survivor.attributes().get(ATTR_TAGS)));
        Set<String> watchers = new LinkedHashSet<>(survivor.watchers());
        for (OperationalObject src : absorbed) {
            moved += movePart(src.id(), survivorId, null);
            tags.addAll(csvTags(src.attributes().get(ATTR_TAGS)));
            watchers.addAll(src.watchers());
            link(src.id(), survivorId, LinkRelationship.MERGED_INTO, actor);
            store.update(src.withAttributes(Map.of(ATTR_MERGED_INTO, survivorId), now)
                    .withStatus("CLOSED", now, true));
            comment(src.id(), actor, "Merged into " + survivorId + (actor == null ? "" : " by " + actor) + ".");
            comment(survivorId, actor, "Absorbed " + src.id() + " (\"" + src.title() + "\")"
                    + (actor == null ? "" : " by " + actor) + ".");
            EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                    .level(EventLevel.INFO)
                    .source(SOURCE)
                    .correlationId(src.correlationId())
                    .message("CASE " + src.id() + " merged into " + survivorId
                            + (actor == null ? "" : " by " + actor))
                    .attr("objectId", src.id())
                    .attr("action", "merge")
                    .attr("survivor", survivorId)
                    .attr("actor", actor));
        }
        Map<String, String> union = new LinkedHashMap<>();
        if (!tags.isEmpty()) union.put(ATTR_TAGS, String.join(",", tags));
        if (!watchers.isEmpty()) union.put(ATTR_WATCHERS, String.join(",", watchers));
        OperationalObject updated = union.isEmpty() ? require(survivorId)
                : store.update(require(survivorId).withAttributes(union, now));
        return new MergeResult(updated, absorbed.stream().map(OperationalObject::id).toList(), moved);
    }

    /**
     * <b>Split</b> the listed {@code members} out of case {@code caseId} into a newly opened case
     * titled {@code title}, managed individually from here on. The new part inherits the original's
     * category + tags, the members' {@code CONTAINS} links move over, a {@code SPLIT_FROM} trace link
     * + comments record the split, and the original stays active with its remaining members. An
     * optional {@code assignee}/{@code queueId} routes the new part.
     *
     * @throws NoSuchElementException   unknown case id
     * @throws IllegalArgumentException blank title / empty members
     * @throws IllegalStateException    a non-CASE or closed case, or a member the case does not contain
     */
    public SplitResult splitCase(String caseId, String title, List<String> members,
                                 String assignee, String queueId, String actor) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("split needs a 'title' for the new case");
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("split needs at least one member");
        OperationalObject original = requireActiveCase(caseId, "split");
        Set<String> contained = new LinkedHashSet<>();
        for (ObjectLink l : links.incident(caseId)) {
            if (l.fromId().equals(caseId) && LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship()))
                contained.add(l.toId());
        }
        for (String m : members) {
            if (!contained.contains(m))
                throw new IllegalStateException("case " + caseId + " does not contain member '" + m + "'");
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        String category = original.attributes().get("category");
        String tags = original.attributes().get(ATTR_TAGS);
        if (category != null) attrs.put("category", category);
        if (tags != null) attrs.put(ATTR_TAGS, tags);
        OperationalObject part = open(ObjectType.CASE, title, "Split from " + caseId + ": " + original.title(),
                original.severity(), original.priority(), original.owner(), null, original.correlationId(), attrs);
        int moved = movePart(caseId, part.id(), new LinkedHashSet<>(members));
        link(part.id(), caseId, LinkRelationship.SPLIT_FROM, actor);
        comment(caseId, actor, "Split " + moved + " member(s) out into " + part.id() + " (\"" + title + "\")"
                + (actor == null ? "" : " by " + actor) + ".");
        comment(part.id(), actor, "Split from " + caseId + (actor == null ? "" : " by " + actor) + ".");
        if ((assignee != null && !assignee.isBlank()) || (queueId != null && !queueId.isBlank()))
            assign(part.id(), assignee, queueId, actor);
        EventLog.current().emit(Event.builder(EventType.OBJECT_ACTIVITY)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(original.correlationId())
                .message("CASE " + part.id() + " split from " + caseId + " (" + moved + " member(s))"
                        + (actor == null ? "" : " by " + actor))
                .attr("objectId", part.id())
                .attr("action", "split")
                .attr("original", caseId)
                .attr("membersMoved", moved)
                .attr("actor", actor));
        return new SplitResult(require(part.id()), moved);
    }

    /**
     * Re-point {@code CONTAINS} member edges from {@code fromCase} to {@code toCase} — all of them, or
     * only {@code onlyMembers} when non-null. Idempotent per member; returns how many edges moved.
     */
    private int movePart(String fromCase, String toCase, Set<String> onlyMembers) {
        int moved = 0;
        for (ObjectLink l : links.incident(fromCase)) {
            if (!l.fromId().equals(fromCase) || !LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship())) continue;
            if (onlyMembers != null && !onlyMembers.contains(l.toId())) continue;
            links.remove(fromCase, l.toId(), LinkRelationship.CONTAINS);
            link(toCase, l.toId(), LinkRelationship.CONTAINS, null);   // idempotent add + OBJECT_LINKED audit
            moved++;
        }
        return moved;
    }

    /** The object must exist, be a CASE, and not be closed/merged — the precondition for group operations. */
    private OperationalObject requireActiveCase(String id, String what) {
        OperationalObject o = require(id);
        if (o.objectType() != ObjectType.CASE)
            throw new IllegalStateException(what + " must be a CASE, but " + id + " is a " + o.objectType());
        if (o.isClosed() || o.attributes().containsKey(ATTR_MERGED_INTO))
            throw new IllegalStateException(what + " " + id + " is closed"
                    + (o.attributes().containsKey(ATTR_MERGED_INTO) ? " (already merged)" : ""));
        return o;
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
