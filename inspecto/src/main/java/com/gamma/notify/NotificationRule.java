package com.gamma.notify;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a triggering {@link Event} to an in-app {@link Notification}. The rule decouples <em>what</em> to
 * notify about (event type + minimum severity) from the <em>copy</em> ({@link NotificationTemplate}
 * title/body) and the de-duplication key — so wording can move to config without touching code (the TOON
 * {@code notifications} section is a planned follow-on; the built-in {@link NotificationRules} ships first).
 *
 * @param eventType         the {@link com.gamma.event.EventType} this rule fires on (case-insensitive)
 * @param minLevel          minimum severity, or {@code null} for any
 * @param category          notification category (also the preference key gating delivery)
 * @param titleTemplate     {@code {{var}}} template for the headline
 * @param bodyTemplate      {@code {{var}}} template for the detail line
 * @param dedupeKeyTemplate {@code {{var}}} template for the collapse key (repeat suppression)
 * @since 4.4.0
 */
public record NotificationRule(String eventType, EventLevel minLevel, String category,
                               String titleTemplate, String bodyTemplate, String dedupeKeyTemplate) {

    /** {@code true} when {@code e} should fire this rule. */
    public boolean matches(Event e) {
        return e != null && eventType.equalsIgnoreCase(e.type())
                && (minLevel == null || e.level().atLeast(minLevel));
    }

    /** Render a fresh UNREAD notification for {@code e} from this rule's templates. */
    public Notification render(Event e) {
        Map<String, Object> ctx = context(e);
        return Notification.create(category, e.type(), e.correlationId(),
                NotificationTemplate.render(titleTemplate, ctx),
                NotificationTemplate.render(bodyTemplate, ctx),
                NotificationTemplate.render(dedupeKeyTemplate, ctx));
    }

    /** The interpolation context for an event: top-level fields, its {@code attributes}, and the recipient. */
    static Map<String, Object> context(Event e) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("eventId", e.eventId());
        ctx.put("type", e.type());
        ctx.put("level", e.level().name());
        ctx.put("source", e.source());
        ctx.put("pipeline", e.pipeline());
        ctx.put("correlationId", e.correlationId());
        ctx.put("message", e.message());
        ctx.put("attributes", e.attributes());
        // In the auth-free core the recipient is always appUser; an edition fills in real identity.
        ctx.put("recipient", Map.of("first_name", "appUser", "name", "appUser"));
        return ctx;
    }
}
