package com.gamma.notify;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a triggering {@link Event} to an in-app {@link Notification}. The rule decouples <em>what</em> to
 * notify about (event type + minimum severity) from the <em>copy</em> ({@link NotificationTemplate}
 * title/body) and the de-duplication key — so wording can move to config without touching code. Built-in
 * rules ({@link NotificationRules#defaults()}) use a synthetic {@code id}; authored rules are persisted as
 * a {@code notification-rule} component via the {@code /notifications/rules*} admin CRUD (mirrors
 * {@link ChannelConfig}) and checked ahead of the built-ins by {@link NotificationRules#forEvent}.
 *
 * @param id                unique identifier (storage key for authored rules; a synthetic slug for built-ins)
 * @param eventType         the {@link com.gamma.event.EventType} this rule fires on (case-insensitive)
 * @param minLevel          minimum severity, or {@code null} for any
 * @param category          notification category (also the preference key gating delivery)
 * @param titleTemplate     {@code {{var}}} template for the headline
 * @param bodyTemplate      {@code {{var}}} template for the detail line
 * @param dedupeKeyTemplate {@code {{var}}} template for the collapse key (repeat suppression)
 * @param enabled           {@code false} disables the rule without deleting it
 * @since 4.4.0
 */
public record NotificationRule(String id, String eventType, EventLevel minLevel, String category,
                               String titleTemplate, String bodyTemplate, String dedupeKeyTemplate,
                               boolean enabled) {

    /** {@code true} when {@code e} should fire this rule. */
    public boolean matches(Event e) {
        return enabled && e != null && eventType.equalsIgnoreCase(e.type())
                && (minLevel == null || e.level().atLeast(minLevel));
    }

    /**
     * Parse + validate an authored rule from a request/stored map. {@code id}/{@code eventType}/{@code
     * category} are required (blank → {@link IllegalArgumentException} → 422); {@code titleTemplate}/
     * {@code bodyTemplate}/{@code dedupeKeyTemplate} default to {@code {{message}}}/{@code {{type}}} when
     * absent; {@code minLevel} is optional ({@code null}/blank ⇒ any severity; an unknown name → 422);
     * {@code enabled} defaults true.
     */
    public static NotificationRule fromMap(Map<String, Object> m) {
        String id = require(m, "id");
        String eventType = require(m, "eventType");
        String category = require(m, "category");
        EventLevel minLevel = level(str(m, "minLevel"));
        String title = m.get("titleTemplate") == null ? "{{type}}" : String.valueOf(m.get("titleTemplate"));
        String body = m.get("bodyTemplate") == null ? "{{message}}" : String.valueOf(m.get("bodyTemplate"));
        String dedupe = m.get("dedupeKeyTemplate") == null ? "{{type}}:{{correlationId}}"
                : String.valueOf(m.get("dedupeKeyTemplate"));
        boolean enabled = !(m.get("enabled") instanceof Boolean b) || b;
        return new NotificationRule(id, eventType, minLevel, category, title, body, dedupe, enabled);
    }

    /** The wire shape the UI's rule editor consumes. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("eventType", eventType);
        out.put("minLevel", minLevel == null ? null : minLevel.name());
        out.put("category", category);
        out.put("titleTemplate", titleTemplate);
        out.put("bodyTemplate", bodyTemplate);
        out.put("dedupeKeyTemplate", dedupeKeyTemplate);
        out.put("enabled", enabled);
        return out;
    }

    private static EventLevel level(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return EventLevel.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown minLevel '" + name + "'");
        }
    }

    private static String require(Map<String, Object> m, String key) {
        String v = str(m, key);
        if (v == null) throw new IllegalArgumentException("id, eventType and category are required");
        return v;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
    }

    /** Render a fresh UNREAD notification for {@code e} from this rule's templates. */
    public Notification render(Event e) {
        Map<String, Object> ctx = context(e);
        return Notification.create(category, e.type(), e.correlationId(),
                NotificationTemplate.render(titleTemplate, ctx),
                NotificationTemplate.render(bodyTemplate, ctx),
                NotificationTemplate.render(dedupeKeyTemplate, ctx));
    }

    /** The interpolation context for an event: top-level fields, {@code time} (event-signal-backbone-plan
     *  §4.4's {@code {{time}}}), the legacy flat {@code attributes} and the structured {@code payload}
     *  (S0's {@link Event#payload()}) side by side, and the recipient. */
    static Map<String, Object> context(Event e) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("eventId", e.eventId());
        ctx.put("type", e.type());
        ctx.put("level", e.level().name());
        ctx.put("source", e.source());
        ctx.put("pipeline", e.pipeline());
        ctx.put("correlationId", e.correlationId());
        ctx.put("message", e.message());
        ctx.put("ts", e.ts());
        ctx.put("time", e.timestamp());
        ctx.put("attributes", e.attributes());
        ctx.put("payload", e.payload());
        // In the auth-free core the recipient is always appUser; an edition fills in real identity.
        ctx.put("recipient", Map.of("first_name", "appUser", "name", "appUser"));
        return ctx;
    }
}
