package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.ObjectNote;
import com.gamma.ops.rca.RcaTemplate;
import com.gamma.ops.tag.CaseRule;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Alert Center / operational-object routes ({@code /objects*}, {@code /rca/templates}; v4.3.0
 * Phase 2-4): create, lifecycle transitions, correlation links + graph, comments, attachments and
 * RCA seeding. Extracted verbatim from {@link ControlApi}: identical routes, order and HTTP statuses.
 */
final class ObjectRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/objects", (e, m) -> toObjectMaps(visibleOnly(api.service().objects().query(objectQuery(e)), e)));
        // Registered before the /objects/{id} catch-all so "analytics" is not read as an id (C4).
        api.get("/objects/analytics", (e, m) -> api.service().objects().analytics(parseObjectType(ApiContext.query(e, "type"))));
        api.post("/objects", (e, m) -> createObject(api, api.body(e)));
        // Every by-id route runs behind the SEC-7d data-scope guard: an object whose caseType is outside
        // the caller's dataScopes answers 404, indistinguishable from absence (existence-hiding).
        api.post("/objects/([^/]+)/ack", scoped(api, (e, m) -> transition(api, ApiContext.name(m), "ack", null, api.body(e))));
        api.post("/objects/([^/]+)/resolve", scoped(api, (e, m) -> transition(api, ApiContext.name(m), "resolve", null, api.body(e))));
        api.post("/objects/([^/]+)/transition", scoped(api, (e, m) -> transitionFromBody(api, ApiContext.name(m), api.body(e))));
        api.post("/objects/([^/]+)/assign", scoped(api, (e, m) -> assign(api, ApiContext.name(m), api.body(e))));
        api.post("/objects/([^/]+)/watch", scoped(api, (e, m) -> setWatch(api, ApiContext.name(m), api.body(e), true)));
        api.post("/objects/([^/]+)/unwatch", scoped(api, (e, m) -> setWatch(api, ApiContext.name(m), api.body(e), false)));
        api.get("/objects/([^/]+)/watchers", scoped(api, (e, m) -> watchersOf(api, ApiContext.name(m))));
        api.post("/objects/([^/]+)/links", scoped(api, (e, m) -> createLink(api, ApiContext.name(m), api.body(e))));
        api.get("/objects/([^/]+)/links", scoped(api, (e, m) -> toLinkMaps(api.service().objects().linksOf(ApiContext.name(m)))));
        api.delete("/objects/([^/]+)/links", scoped(api, (e, m) -> deleteLink(api, ApiContext.name(m), e)));
        api.post("/objects/([^/]+)/merge", scoped(api, (e, m) -> mergeCases(api, ApiContext.name(m), api.body(e))));
        api.post("/objects/([^/]+)/split", scoped(api, (e, m) -> splitCase(api, ApiContext.name(m), api.body(e))));
        api.get("/objects/([^/]+)/graph", scoped(api, (e, m) -> objectGraph(api, ApiContext.name(m), e)));
        api.post("/objects/([^/]+)/comments", scoped(api, (e, m) -> addComment(api, ApiContext.name(m), api.body(e))));
        api.get("/objects/([^/]+)/comments", scoped(api, (e, m) -> toNoteMaps(api.service().objects().notesOf(ApiContext.name(m), NoteKind.COMMENT))));
        api.post("/objects/([^/]+)/attachments", scoped(api, (e, m) -> addAttachment(api, ApiContext.name(m), api.body(e))));
        api.get("/objects/([^/]+)/attachments", scoped(api, (e, m) -> toNoteMaps(api.service().objects().notesOf(ApiContext.name(m), NoteKind.ATTACHMENT))));
        api.post("/objects/([^/]+)/rca", scoped(api, (e, m) -> applyRca(api, ApiContext.name(m), api.body(e))));
        api.patch("/objects/([^/]+)", scoped(api, (e, m) -> patchObject(api, ApiContext.name(m), api.body(e))));
        api.get("/objects/([^/]+)", scoped(api, (e, m) -> objectById(api, ApiContext.name(m))));
        api.get("/rca/templates", (e, m) -> rcaTemplateList(api));
        // The effective (possibly *_workflow.toon-overridden) lifecycle for a type — lets the UI derive
        // folders + action verbs instead of hardcoding state lists (case-management-design.md C6).
        api.get("/workflows/([^/]+)", (e, m) -> workflowOf(api, ApiContext.name(m)));
        // Rule-raised cases (C5): auto-group Incidents into a Case. CRUD is capability-gated (config);
        // evaluate mutates objects (an operational action, like transition), so it is ungated.
        api.get("/cases/rules", (e, m) -> api.service().objects().caseRules().stream().map(CaseRule::toMap).toList());
        api.post("/cases/rules", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> saveCaseRule(api, api.body(e))));
        api.delete("/cases/rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> deleteCaseRule(api, ApiContext.name(m))));
        api.post("/cases/rules/([^/]+)/evaluate", (e, m) -> evaluateCaseRule(api, ApiContext.name(m)));
    }

    // ── rule-raised cases (C5) ────────────────────────────────────────────────────────

    /**
     * {@code POST /cases/rules} — save (create or replace) a Case Rule; body {@code {name, title,
     * filter:{…}, threshold?, windowMinutes?, category?, tags?}}. At least one filter criterion is
     * required (422); persisted as {@code <name>_caserule.toon} under the write root.
     */
    private Object saveCaseRule(ApiContext api, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "case rule write");
        CaseRule rule;
        try {
            rule = CaseRule.fromMap(body);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
        Path file = caseRuleFile(api, rule.name());
        byte[] bytes = ConfigCodec.toToon(Map.of("case_rule", rule.toMap())).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(file, bytes, ".caserule-");
        return api.service().objects().registerCaseRule(rule).toMap();
    }

    /** {@code DELETE /cases/rules/{name}} — remove a rule (registry + persisted file); 404 if unknown. */
    private Object deleteCaseRule(ApiContext api, String name) throws IOException {
        WriteGates.requireWriteRoot(api, "case rule write");
        if (api.service().objects().caseRule(name).isEmpty())
            throw new ApiException(404, "no case rule named '" + name + "'");
        boolean fileRemoved = Files.deleteIfExists(caseRuleFile(api, name));
        api.service().objects().removeCaseRule(name);
        return Map.of("deleted", name, "fileRemoved", fileRemoved);
    }

    /**
     * {@code POST /cases/rules/{name}/evaluate} — auto-group matching Incidents into a Case (C5).
     * Returns {@code {matched, grouped, caseId, opened}}; unknown rule → 404.
     */
    private Object evaluateCaseRule(ApiContext api, String name) {
        try {
            ObjectService.CaseRuleEvaluation r = api.service().objects().evaluateCaseRule(name);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("matched", r.matched());
            out.put("grouped", r.grouped());
            out.put("caseId", r.caseId());
            out.put("opened", r.opened());
            return out;
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    /** The jailed {@code <name>_caserule.toon} path under the write root; 422 on an unsafe name, 403 on escape. */
    private static Path caseRuleFile(ApiContext api, String name) {
        String safe = WriteGates.safeName(name, "case rule name");
        Path root = api.writeRoot();
        return WriteGates.jail(root, root.resolve(safe + "_caserule.toon"), "resolved path");
    }

    /** {@code GET /workflows/{type}} — the effective workflow definition; unknown type → 400. */
    private Object workflowOf(ApiContext api, String type) {
        return api.service().objects().workflow(parseObjectType(type)).toMap();
    }

    // ── SEC-7d data-scoped grants ("a fraud analyst sees fraud cases") ───────────────

    /** Attribute key carrying an object's case type — the dimension {@link Subject#dataScopes()} filters on. */
    static final String ATTR_CASE_TYPE = "caseType";

    /**
     * Whether the caller may see {@code o}: an unscoped caller (Personal — no Subject; or a role with
     * {@code dataScopes = null}) sees everything; a scoped caller sees untyped objects plus those whose
     * {@code caseType} is in their scopes. Fail-closed: an empty scope set reveals only untyped objects.
     */
    private static boolean visibleTo(HttpExchange ex, OperationalObject o) {
        Subject s = ApiContext.subject(ex).orElse(null);
        if (s == null || !s.scoped()) return true;
        String caseType = o.attributes().get(ATTR_CASE_TYPE);
        return caseType == null || caseType.isBlank() || s.dataScopes().contains(caseType);
    }

    private static List<OperationalObject> visibleOnly(List<OperationalObject> objs, HttpExchange ex) {
        return objs.stream().filter(o -> visibleTo(ex, o)).toList();
    }

    /** Wrap a by-id handler: out-of-scope answers the same 404 an absent id does (existence-hiding). */
    private Handler scoped(ApiContext api, Handler h) {
        return (e, m) -> {
            String id = ApiContext.name(m);
            OperationalObject o = api.service().objects().get(id).orElse(null);
            if (o != null && !visibleTo(e, o))
                throw new ApiException(404, "no object with id '" + id + "'");
            return h.handle(e, m);   // absent ids keep their existing 404/behaviour
        };
    }

    private static List<Map<String, Object>> toObjectMaps(List<OperationalObject> objs) {
        return objs.stream().map(OperationalObject::toMap).toList();
    }

    /** Build an {@link ObjectQuery} from {@code ?type=&status=&severity=&assignee=&owner=&correlationId=&q=&limit=&offset=}. */
    private static ObjectQuery objectQuery(HttpExchange ex) {
        return ObjectQuery.builder()
                .objectType(parseObjectType(ApiContext.query(ex, "type")))
                .status(ApiContext.query(ex, "status"))
                .severity(ApiContext.query(ex, "severity"))
                .assignee(ApiContext.query(ex, "assignee"))
                .owner(ApiContext.query(ex, "owner"))
                .correlationId(ApiContext.query(ex, "correlationId"))
                .textContains(ApiContext.query(ex, "q"))
                .limit(ApiContext.parseIntOr(ApiContext.query(ex, "limit"), ObjectQuery.DEFAULT_LIMIT))
                .offset(ApiContext.parseIntOr(ApiContext.query(ex, "offset"), 0))
                .build();
    }

    /** Parse a {@code ?type=} filter; an unknown value is a 400 rather than a silent match-everything. */
    private static ObjectType parseObjectType(String s) {
        try {
            return ObjectType.of(s);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** {@code GET /objects/{id}} — the object, or 404. */
    private Object objectById(ApiContext api, String id) {
        return api.service().objects().get(id).map(OperationalObject::toMap)
                .orElseThrow(() -> new ApiException(404, "no object with id '" + id + "'"));
    }

    /**
     * {@code POST /objects} (Phase 3) — create a managed object. The complement of alert auto-promotion:
     * ALERTs are opened by the {@code AlertService}, whereas INCIDENTs are operator-created here. Body
     * {@code {type?,title,description?,severity?,priority?,owner?,assignee?,correlationId?,attributes?,
     * dueAt?|dueInMinutes?}} — {@code type} defaults to {@code INCIDENT}, {@code title} is required, and
     * {@code dueAt} (epoch millis) or {@code dueInMinutes} sets the SLA deadline the sweep tracks. The
     * object opens in its workflow's initial state; lifecycle moves go through {@code /objects/{id}/transition}.
     */
    private Object createObject(ApiContext api, Map<String, Object> body) {
        String title = ApiContext.str(body, "title");
        if (title == null) throw new ApiException(400, "body must include 'title'");
        ObjectType type;
        try {
            type = ObjectType.of(ApiContext.str(body, "type"));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, ex.getMessage());
        }
        if (type == null) type = ObjectType.INCIDENT;   // the create path exists for operator-created incidents

        Map<String, String> attrs = new LinkedHashMap<>();
        if (body.get("attributes") instanceof Map<?, ?> bag)
            bag.forEach((k, v) -> { if (k != null && v != null) attrs.put(k.toString(), v.toString()); });
        Long dueAt = parseDueAt(body);
        if (dueAt != null) attrs.put(ObjectService.ATTR_DUE_AT, Long.toString(dueAt));

        return api.service().objects().open(type, title, ApiContext.str(body, "description"), ApiContext.str(body, "severity"),
                ApiContext.str(body, "priority"), ApiContext.str(body, "owner"), ApiContext.str(body, "assignee"),
                ApiContext.str(body, "correlationId"), attrs).toMap();
    }

    /** SLA deadline from the create body: absolute {@code dueAt} (epoch millis) or relative {@code dueInMinutes}. */
    private static Long parseDueAt(Map<String, Object> body) {
        Object due = body.get("dueAt");
        if (due != null) {
            long ms = parseLongOr(due.toString(), -1L);
            if (ms > 0) return ms;
        }
        Object mins = body.get("dueInMinutes");
        if (mins != null) {
            long m = parseLongOr(mins.toString(), -1L);
            if (m >= 0) return System.currentTimeMillis() + m * 60_000L;
        }
        return null;
    }

    private static long parseLongOr(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * {@code POST /objects/{id}/links} (Phase 4) — correlate this object with another: body
     * {@code {to, relationship?, actor?}} (e.g. a CASE {@code CONTAINS} an INCIDENT). A missing {@code to}
     * → 400; an unknown {@code id} or {@code to} → 404. Idempotent (a duplicate edge returns the existing one).
     */
    private Object createLink(ApiContext api, String fromId, Map<String, Object> body) {
        String to = ApiContext.str(body, "to");
        if (to == null) throw new ApiException(400, "body must include 'to'");
        try {
            return api.service().objects().link(fromId, to, ApiContext.str(body, "relationship"), ApiContext.str(body, "actor")).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    private static List<Map<String, Object>> toLinkMaps(List<ObjectLink> links) {
        return links.stream().map(ObjectLink::toMap).toList();
    }

    /**
     * {@code DELETE /objects/{id}/links?to=&relationship=} (case group management) — remove one edge
     * (e.g. taking a member incident out of a Case's Contents). Missing {@code to} → 400; unknown
     * object or edge → 404. The removal is audited on the Event Log.
     */
    private Object deleteLink(ApiContext api, String fromId, HttpExchange ex) {
        String to = ApiContext.query(ex, "to");
        String relationship = ApiContext.query(ex, "relationship");
        if (to == null || to.isBlank()) throw new ApiException(400, "query must include 'to'");
        try {
            if (!api.service().objects().unlink(fromId, to, relationship, ApiContext.query(ex, "actor")))
                throw new ApiException(404, "no such link " + fromId + " -> " + to);
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
        return Map.of("from", fromId, "to", to, "deleted", true);
    }

    /**
     * {@code POST /objects/{id}/merge} (GLOSSARY §9 — Merge) — absorb the body's {@code sources} cases
     * into this surviving case: members re-point, tags/watchers union, sources close with a
     * {@code MERGED_INTO} trace. Body {@code {sources:[caseId…], actor?}}. Empty sources → 400;
     * unknown ids → 404; non-CASE / self-merge / already-closed-or-merged → 422.
     */
    private Object mergeCases(ApiContext api, String survivorId, Map<String, Object> body) {
        List<String> sources = stringList(body.get("sources"));
        if (sources.isEmpty()) throw new ApiException(400, "body must include non-empty 'sources'");
        try {
            var result = api.service().objects().mergeCases(survivorId, sources, ApiContext.str(body, "actor"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("survivor", result.survivor().toMap());
            out.put("merged", result.merged());
            out.put("membersMoved", result.membersMoved());
            return out;
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalStateException illegal) {
            throw new ApiException(422, illegal.getMessage());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }
    }

    /**
     * {@code POST /objects/{id}/split} (GLOSSARY §9 — Split) — carve the listed member incidents out of
     * this case into a new case managed individually. Body {@code {title, members:[incidentId…],
     * assignee?|queue?, actor?}}; repeat the call for multi-way splits. Blank title / empty members →
     * 400; unknown case → 404; non-CASE / closed case / a member not contained → 422.
     */
    private Object splitCase(ApiContext api, String caseId, Map<String, Object> body) {
        String title = ApiContext.str(body, "title");
        List<String> members = stringList(body.get("members"));
        if (title == null || title.isBlank()) throw new ApiException(400, "body must include 'title'");
        if (members.isEmpty()) throw new ApiException(400, "body must include non-empty 'members'");
        try {
            var result = api.service().objects().splitCase(caseId, title, members,
                    ApiContext.str(body, "assignee"), ApiContext.str(body, "queue"), ApiContext.str(body, "actor"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("case", result.part().toMap());
            out.put("membersMoved", result.membersMoved());
            return out;
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalStateException illegal) {
            throw new ApiException(422, illegal.getMessage());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }
    }

    /** The body value as a trimmed, non-empty string list (a JSON array of ids). */
    private static List<String> stringList(Object v) {
        List<String> out = new java.util.ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String s = o.toString().trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /**
     * {@code GET /objects/{id}/graph?depth=} (Phase 4) — correlation subgraph (default depth 2, capped at 5).
     * SEC-7d: out-of-scope neighbours are pruned from the result (nodes dropped, edges touching them dropped),
     * so a scoped analyst's graph never names case data outside their grants.
     */
    @SuppressWarnings("unchecked")
    private Object objectGraph(ApiContext api, String id, HttpExchange ex) {
        int depth = Math.min(5, Math.max(1, ApiContext.parseIntOr(ApiContext.query(ex, "depth"), 2)));
        Map<String, Object> g;
        try {
            g = api.service().objects().graph(id, depth);
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) g.get("nodes");
        java.util.Set<String> visible = new java.util.HashSet<>();
        List<Map<String, Object>> keptNodes = nodes.stream().filter(n -> {
            String nid = String.valueOf(n.get("id"));
            boolean ok = api.service().objects().get(nid).map(o -> visibleTo(ex, o)).orElse(false);
            if (ok) visible.add(nid);
            return ok;
        }).toList();
        List<Map<String, Object>> keptEdges = ((List<Map<String, Object>>) g.get("edges")).stream()
                .filter(l -> visible.contains(String.valueOf(l.get("from")))
                        && visible.contains(String.valueOf(l.get("to")))).toList();
        g.put("nodes", keptNodes);
        g.put("edges", keptEdges);
        return g;
    }

    /** {@code POST /objects/{id}/comments} (Phase 4) — add a comment; body {@code {body, author?}}. */
    private Object addComment(ApiContext api, String id, Map<String, Object> body) {
        String text = ApiContext.str(body, "body");
        if (text == null) throw new ApiException(400, "body must include 'body'");
        try {
            return api.service().objects().comment(id, ApiContext.str(body, "author"), text).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    /**
     * {@code POST /objects/{id}/attachments} (Phase 4) — attach an evidence reference (metadata only);
     * body {@code {name, uri, contentType?, author?, caption?}}.
     */
    private Object addAttachment(ApiContext api, String id, Map<String, Object> body) {
        String name = ApiContext.str(body, "name");
        String uri = ApiContext.str(body, "uri");
        if (name == null || uri == null) throw new ApiException(400, "body must include 'name' and 'uri'");
        try {
            return api.service().objects().attach(id, ApiContext.str(body, "author"), name, ApiContext.str(body, "contentType"),
                    uri, ApiContext.str(body, "caption")).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    private static List<Map<String, Object>> toNoteMaps(List<ObjectNote> notes) {
        return notes.stream().map(ObjectNote::toMap).toList();
    }

    /**
     * {@code POST /objects/{id}/rca} (Phase 4) — seed an RCA skeleton (one comment per section). Body is
     * the template: {@code {template:{name,sections[]}}} or an inline {@code {name?,sections[],actor?}}.
     */
    private Object applyRca(ApiContext api, String id, Map<String, Object> body) {
        RcaTemplate template;
        Object t = body.get("template");
        if (t instanceof String named) {       // a *_rca.toon template referenced by name
            template = api.service().rcaTemplate(named).orElseThrow(
                    () -> new ApiException(404, "no RCA template named '" + named + "'"));
        } else {                                // an inline template ({template:{…}} or the body itself)
            Map<String, Object> tmpl = new LinkedHashMap<>();
            if (t instanceof Map<?, ?> tm) tm.forEach((k, v) -> tmpl.put(String.valueOf(k), v));
            else tmpl.putAll(body);
            tmpl.putIfAbsent("name", "ad-hoc"); // an inline template needn't name itself
            try {
                template = RcaTemplate.fromMap(tmpl);
            } catch (IllegalArgumentException ex) {
                throw new ApiException(400, ex.getMessage());
            }
        }
        try {
            return toNoteMaps(api.service().objects().applyRca(id, template, ApiContext.str(body, "actor")));
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    /** {@code GET /rca/templates} (Phase 4) — the RCA templates loaded from {@code *_rca.toon}, by name. */
    private Object rcaTemplateList(ApiContext api) {
        return api.service().rcaTemplates().values().stream().map(RcaTemplate::toMap).toList();
    }

    /**
     * {@code POST /objects/{id}/assign} (INC-4) — assign to a person or route through a queue: body
     * {@code {assignee?|queue?, actor?}}. An explicit {@code assignee} wins; else the {@code queue}'s router
     * picks a member. Missing both → 400; unknown object/queue → 404; an unroutable queue (empty / manual
     * without an assignee) → 422.
     */
    private Object assign(ApiContext api, String id, Map<String, Object> body) {
        String assignee = ApiContext.str(body, "assignee");
        String queue = ApiContext.str(body, "queue");
        if (assignee == null && queue == null)
            throw new ApiException(400, "body must include 'assignee' or 'queue'");
        try {
            return api.service().objects().assign(id, assignee, queue, ApiContext.str(body, "actor")).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalStateException illegal) {
            throw new ApiException(422, illegal.getMessage());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }
    }

    /**
     * {@code PATCH /objects/{id}} — partial update of the operator-mutable fields; body any of
     * {@code {priority?, severity?, assignee?, attributes?}} (attributes merge over the stored bag,
     * updates win). The mail view's Prioritize / tagging / postmortem saves ride this. At least one
     * field → else 400; unknown id → 404. No workflow involvement — status changes stay on
     * {@code /objects/{id}/transition}.
     */
    private Object patchObject(ApiContext api, String id, Map<String, Object> body) {
        Map<String, String> attrs = null;
        if (body.get("attributes") instanceof Map<?, ?> bag) {
            Map<String, String> collected = new LinkedHashMap<>();
            bag.forEach((k, v) -> { if (k != null && v != null) collected.put(k.toString(), v.toString()); });
            attrs = collected;
        }
        String priority = ApiContext.str(body, "priority");
        String severity = ApiContext.str(body, "severity");
        String assignee = ApiContext.str(body, "assignee");
        if (priority == null && severity == null && assignee == null && attrs == null)
            throw new ApiException(400, "body must include at least one of 'priority', 'severity', 'assignee', 'attributes'");
        try {
            return api.service().objects().patch(id, priority, severity, assignee, attrs).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    /** {@code POST /objects/{id}/watch|unwatch} (INC-4) — subscribe/unsubscribe a watcher; body {@code {user}}. */
    private Object setWatch(ApiContext api, String id, Map<String, Object> body, boolean add) {
        String user = ApiContext.str(body, "user");
        if (user == null) throw new ApiException(400, "body must include 'user'");
        try {
            return (add ? api.service().objects().watch(id, user)
                        : api.service().objects().unwatch(id, user)).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    /** {@code GET /objects/{id}/watchers} (INC-4) — the object's watcher list. */
    private Object watchersOf(ApiContext api, String id) {
        return api.service().objects().get(id).map(OperationalObject::watchers)
                .orElseThrow(() -> new ApiException(404, "no object with id '" + id + "'"));
    }

    /** {@code POST /objects/{id}/ack|resolve} — a fixed-action transition; {@code actor} from the body. */
    private Object transition(ApiContext api, String id, String action, String target, Map<String, Object> body) {
        return doTransition(api, id, action, target, ApiContext.str(body, "actor"));
    }

    /** {@code POST /objects/{id}/transition} — body {@code {action}} or {@code {status|to}} (+ optional {@code actor}). */
    private Object transitionFromBody(ApiContext api, String id, Map<String, Object> body) {
        String action = ApiContext.str(body, "action");
        String target = ApiContext.str(body, "status");
        if (target == null) target = ApiContext.str(body, "to");
        if (action == null && target == null)
            throw new ApiException(400, "body must include 'action' or 'status'");
        return doTransition(api, id, action, target, ApiContext.str(body, "actor"));
    }

    /** Apply a lifecycle transition, mapping the service's exceptions to 404 (unknown id) / 422 (illegal move). */
    private Object doTransition(ApiContext api, String id, String action, String target, String actor) {
        try {
            OperationalObject updated = (action != null)
                    ? api.service().objects().transition(id, action, actor)
                    : api.service().objects().transitionTo(id, target, actor);
            return updated.toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalStateException | IllegalArgumentException illegal) {
            throw new ApiException(422, illegal.getMessage());
        }
    }
}
