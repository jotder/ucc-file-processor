package com.gamma.control;

import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.ObjectNote;
import com.gamma.ops.rca.RcaTemplate;
import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alert Center / operational-object routes ({@code /objects*}, {@code /rca/templates}; v4.3.0
 * Phase 2-4): create, lifecycle transitions, correlation links + graph, comments, attachments and
 * RCA seeding. Extracted verbatim from {@link ControlApi}: identical routes, order and HTTP statuses.
 */
final class ObjectRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/objects", (e, m) -> toObjectMaps(api.service().objects().query(objectQuery(e))));
        api.post("/objects", (e, m) -> createObject(api, api.body(e)));
        api.post("/objects/([^/]+)/ack", (e, m) -> transition(api, ApiContext.name(m), "ack", null, api.body(e)));
        api.post("/objects/([^/]+)/resolve", (e, m) -> transition(api, ApiContext.name(m), "resolve", null, api.body(e)));
        api.post("/objects/([^/]+)/transition", (e, m) -> transitionFromBody(api, ApiContext.name(m), api.body(e)));
        api.post("/objects/([^/]+)/links", (e, m) -> createLink(api, ApiContext.name(m), api.body(e)));
        api.get("/objects/([^/]+)/links", (e, m) -> toLinkMaps(api.service().objects().linksOf(ApiContext.name(m))));
        api.get("/objects/([^/]+)/graph", (e, m) -> objectGraph(api, ApiContext.name(m), e));
        api.post("/objects/([^/]+)/comments", (e, m) -> addComment(api, ApiContext.name(m), api.body(e)));
        api.get("/objects/([^/]+)/comments", (e, m) -> toNoteMaps(api.service().objects().notesOf(ApiContext.name(m), NoteKind.COMMENT)));
        api.post("/objects/([^/]+)/attachments", (e, m) -> addAttachment(api, ApiContext.name(m), api.body(e)));
        api.get("/objects/([^/]+)/attachments", (e, m) -> toNoteMaps(api.service().objects().notesOf(ApiContext.name(m), NoteKind.ATTACHMENT)));
        api.post("/objects/([^/]+)/rca", (e, m) -> applyRca(api, ApiContext.name(m), api.body(e)));
        api.get("/objects/([^/]+)", (e, m) -> objectById(api, ApiContext.name(m)));
        api.get("/rca/templates", (e, m) -> rcaTemplateList(api));
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
     * ALERTs are opened by the {@code AlertService}, whereas ISSUEs are operator-created here. Body
     * {@code {type?,title,description?,severity?,priority?,owner?,assignee?,correlationId?,attributes?,
     * dueAt?|dueInMinutes?}} — {@code type} defaults to {@code ISSUE}, {@code title} is required, and
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
        if (type == null) type = ObjectType.ISSUE;   // the create path exists for operator-created issues

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
     * {@code {to, relationship?, actor?}} (e.g. a CASE {@code CONTAINS} an ISSUE). A missing {@code to}
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

    /** {@code GET /objects/{id}/graph?depth=} (Phase 4) — correlation subgraph (default depth 2, capped at 5). */
    private Object objectGraph(ApiContext api, String id, HttpExchange ex) {
        int depth = Math.min(5, Math.max(1, ApiContext.parseIntOr(ApiContext.query(ex, "depth"), 2)));
        try {
            return api.service().objects().graph(id, depth);
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
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
