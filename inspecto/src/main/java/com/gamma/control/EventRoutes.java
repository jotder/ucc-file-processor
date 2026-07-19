package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventQuery;
import com.gamma.event.SavedView;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operational Event Viewer routes ({@code /events*}, v4.2.0 Phase 1): the append-only "what
 * happened" feed (recent / filtered search / by-id), CSV-or-JSON export, and saved filter views.
 * Extracted verbatim from {@link ControlApi}: identical routes, order, statuses and CSV shape.
 */
final class EventRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        // Legacy: the newest ?limit= events from the live-tail ring, byte-for-byte unchanged. On /api/v1
        // the list is instead cursor-paginated over the full retained history (eventsPage), sharing the route.
        api.get("/events", (e, m) -> ApiContext.v1(e) ? eventsPage(api, e)
                : toMaps(api.service().events().recent(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50))));
        api.get("/events/search", (e, m) -> toMaps(api.service().events().query(eventQuery(e, EventQuery.DEFAULT_LIMIT))));
        api.get("/events/export", (e, m) -> exportEvents(api, e));
        api.get("/events/views", (e, m) -> api.service().savedViews().list());
        api.post("/events/views", (e, m) -> saveView(api, api.body(e)));
        api.post("/events/views/([^/]+)/delete", (e, m) -> {
            if (!api.service().savedViews().delete(ApiContext.name(m)))
                throw new ApiException(404, "no saved view named '" + ApiContext.name(m) + "'");
            return Map.of("name", ApiContext.name(m), "deleted", true);
        });
        api.get("/events/([^/]+)", (e, m) -> eventById(api, ApiContext.name(m)));
    }

    private static List<Map<String, Object>> toMaps(List<Event> events) {
        return events.stream().map(Event::toMap).toList();
    }

    /**
     * {@code GET /api/v1/events?limit=&cursor=} — one cursor-paginated page of the full retained event
     * history (buffer + Parquet), newest first (keyset {@code (ts, eventId)}). The opaque {@code cursor}
     * resumes strictly after the previous page's last row, so pages don't drift as new events land —
     * unlike the legacy view, which only ever serves the live-tail ring. Events are high-volume, so the
     * keyset runs store-side ({@link com.gamma.event.EventStore#page}) — the SQL-predicate variant of the
     * {@code /jobs/runs} adopter, not the in-route {@code /objects} one. The v1 envelope's
     * {@code metadata.pagination} carries {@code cursor/nextCursor/limit/total}.
     */
    private static Object eventsPage(ApiContext api, HttpExchange e) {
        int limit = Math.max(1, Math.min(500, ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50)));
        String cursor = ApiContext.query(e, "cursor");
        List<String> key = Cursor.decode(cursor);
        Long afterTs = key.size() == 2 ? parseLongOrNull(key.get(0)) : null;
        String afterId = key.size() == 2 ? key.get(1) : null;

        List<Event> rows = api.service().events().page(limit + 1, afterTs, afterId);
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = rows.subList(0, limit);
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            Event last = rows.get(rows.size() - 1);
            nextCursor = Cursor.encode(List.of(String.valueOf(last.ts()),
                    last.eventId() == null ? "" : last.eventId()));
        }
        ApiContext.pagination(e, cursor, nextCursor, limit, api.service().events().count());
        return toMaps(rows);
    }

    /** Cursor key parts are strings; an unparsable timestamp part means "start from the top" (decode-total). */
    private static Long parseLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Build an {@link EventQuery} from {@code ?level=&type=&pipeline=&correlationId=&q=&from=&to=&limit=&offset=}. */
    private static EventQuery eventQuery(HttpExchange ex, int defaultLimit) {
        String level = ApiContext.query(ex, "level");
        return EventQuery.builder()
                .minLevel(level == null ? null : EventLevel.parse(level))
                .type(ApiContext.query(ex, "type"))
                .pipeline(ApiContext.query(ex, "pipeline"))
                .correlationId(ApiContext.query(ex, "correlationId"))
                .textContains(ApiContext.query(ex, "q"))
                .from(epochMillis(ApiContext.query(ex, "from")))
                .to(epochMillis(ApiContext.query(ex, "to")))
                .limit(ApiContext.parseIntOr(ApiContext.query(ex, "limit"), defaultLimit))
                .offset(ApiContext.parseIntOr(ApiContext.query(ex, "offset"), 0))
                .build();
    }

    /** Parse a time bound as epoch millis (all-digits) or a {@code yyyy-MM-dd[ HH:mm:ss]} string; null when blank. */
    private static Long epochMillis(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            try { return Long.parseLong(t); } catch (NumberFormatException ignore) { return null; }
        }
        try {
            String norm = (t.length() <= 10 ? t + " 00:00:00" : t.replace('T', ' ')).substring(0, 19);
            return java.time.LocalDateTime.parse(norm,
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            throw new ApiException(400, "invalid time '" + s + "' (use epoch millis or yyyy-MM-dd[ HH:mm:ss])");
        }
    }

    /** {@code GET /events/{id}} — scan the newest events (buffer + Parquet) for an exact id, else 404. */
    private Object eventById(ApiContext api, String id) {
        return api.service().events().query(EventQuery.recent(EventQuery.MAX_LIMIT)).stream()
                .filter(ev -> id.equals(ev.eventId())).findFirst()
                .map(Event::toMap)
                .orElseThrow(() -> new ApiException(404, "no event with id '" + id + "'"));
    }

    /** {@code GET /events/export} — {@code ?format=csv} streams CSV; otherwise returns the JSON list. */
    private Object exportEvents(ApiContext api, HttpExchange ex) throws IOException {
        List<Event> rows = api.service().events().query(eventQuery(ex, EventQuery.MAX_LIMIT));
        if ("csv".equalsIgnoreCase(ApiContext.query(ex, "format"))) {
            return ApiContext.respondText(ex, eventsCsv(rows), "text/csv; charset=utf-8");
        }
        return toMaps(rows);
    }

    private static String eventsCsv(List<Event> rows) {
        StringBuilder sb = new StringBuilder("timestamp,level,type,source,pipeline,correlationId,message\n");
        for (Event e : rows) {
            sb.append(csv(e.timestamp())).append(',').append(csv(e.level().name())).append(',')
              .append(csv(e.type())).append(',').append(csv(e.source())).append(',')
              .append(csv(e.pipeline())).append(',').append(csv(e.correlationId())).append(',')
              .append(csv(e.message())).append('\n');
        }
        return sb.toString();
    }

    /** Minimal RFC-4180 CSV field escape. */
    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
            return '"' + v.replace("\"", "\"\"") + '"';
        return v;
    }

    /** {@code POST /events/views} — upsert a saved view from {@code {name, level?, type?, pipeline?, correlationId?, q?, from?, to?}}. */
    private Object saveView(ApiContext api, Map<String, Object> reqBody) {
        String viewName = ApiContext.str(reqBody, "name");
        if (viewName == null) throw new ApiException(400, "body must include 'name'");
        Map<String, String> filters = new LinkedHashMap<>();
        for (String k : List.of("level", "type", "pipeline", "correlationId", "q", "from", "to")) {
            String v = ApiContext.str(reqBody, k);
            if (v != null) filters.put(k, v);
        }
        return api.service().savedViews().save(new SavedView(viewName, filters, System.currentTimeMillis())).toMap();
    }
}
