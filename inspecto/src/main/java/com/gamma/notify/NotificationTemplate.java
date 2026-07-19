package com.gamma.notify;

import com.gamma.util.DottedPath;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {@code {{var}}} interpolation that decouples notification copy from code — templates carry the
 * wording (and live in config), the engine injects context variables ({@code {{recipient.first_name}}},
 * {@code {{entity.id}}}, {@code {{pipeline}}}, {@code {{attributes.rows}}}). Dotted paths walk nested
 * {@link Map}s; an unresolved or {@code null} variable renders as the empty string (copy never leaks a
 * raw {@code {{token}}} to the user). Deliberately tiny — no new templating dependency.
 *
 * @since 4.4.0
 */
public final class NotificationTemplate {

    private NotificationTemplate() {}

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    /** Render {@code template}, replacing each {@code {{a.b.c}}} with the dotted value from {@code context}. */
    public static String render(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty() || template.indexOf('{') < 0) return template;
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = DottedPath.resolve(context, m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
