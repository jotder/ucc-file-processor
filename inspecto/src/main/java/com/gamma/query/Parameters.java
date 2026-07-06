package com.gamma.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side resolver for the R3 <b>{@code $}-parameter</b> namespace (W4; design §6.2) — the Java
 * mirror of the UI's {@code inspecto/query/parameters.ts}. A query's SQL text may reference
 * {@code $}-tokens resolved at run time from a {@link Context} (session/clock today; scheduler /
 * previous-run / AI decision later — same seam), plus caller-supplied values and declared defaults.
 *
 * <p>Three namespaces coexist and MUST NOT be conflated — the token grammar ({@code $} + identifier,
 * no {@code {}) deliberately never matches the other two:
 * <ul>
 *   <li>{@code $name}      — this: a runtime binding (built-in or declared).</li>
 *   <li>{@code :name}      — a rule/data-table template placeholder (untouched).</li>
 *   <li>{@code ${ENV:KEY}} — a config-time secret reference (untouched; stays server-side).</li>
 * </ul>
 * Values are emitted as SQL literals — dates/strings single-quoted (embedded quotes doubled),
 * numbers raw (after numeric validation). Unknown tokens are left verbatim so a typo stays visible.
 */
public final class Parameters {

    private Parameters() {}

    /** A user-declared parameter: name, value type ({@code date|string|number}), and optional default. */
    public record Def(String name, String type, String defaultValue) {}

    /** Values available when resolving built-in tokens ({@code $today}/{@code $now}/{@code $day}/{@code $current_user}/{@code $role}). */
    public record Context(Instant now, String user, String role) {
        public static Context of(String user, String role) {
            return new Context(Instant.now(), user, role);
        }
    }

    /** {@code $} + identifier, with an optional integer offset ({@code $day(-7)}); never matches {@code ${…}} nor {@code :name}. */
    private static final Pattern TOKEN = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)(?:\\(\\s*(-?\\d+)\\s*\\))?");

    /**
     * Substitute every {@code $}-token in {@code text}: built-ins from {@code ctx}, then a caller-supplied
     * {@code values} entry, then the declared {@code default}. Emits SQL literals. Unknown/undeclared tokens
     * with no value are left verbatim.
     *
     * @throws IllegalArgumentException if a {@code number}-typed parameter's value is not numeric
     */
    public static String resolve(String text, List<Def> defs, Map<String, String> values, Context ctx) {
        if (text == null) return null;
        Map<String, Def> byName = new java.util.HashMap<>();
        if (defs != null) for (Def d : defs) if (d != null && d.name() != null) byName.put(d.name(), d);
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String repl = resolveToken(m.group(1), m.group(2), byName, values, ctx, m.group(0));
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String resolveToken(String name, String arg, Map<String, Def> byName,
                                       Map<String, String> values, Context ctx, String whole) {
        Instant now = ctx != null && ctx.now() != null ? ctx.now() : Instant.now();
        switch (name) {
            case "today":
                return sqlString(isoDate(now));
            case "now":
                return sqlString(now.toString());
            case "day": {
                long offset = arg == null ? 0 : Long.parseLong(arg);
                return sqlString(LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(offset).toString());
            }
            case "current_user":
                return ctx != null && ctx.user() != null ? sqlString(ctx.user()) : whole;
            case "role":
                return ctx != null && ctx.role() != null ? sqlString(ctx.role()) : whole;
            default: {
                Def def = byName.get(name);
                if (def == null) return whole;                       // undeclared → leave visible
                String value = values != null && values.get(name) != null ? values.get(name) : def.defaultValue();
                if (value == null) return whole;                     // no value/default → leave visible
                if ("number".equals(def.type())) {
                    try {
                        Double.parseDouble(value.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "parameter '$" + name + "' value '" + value + "' is not a number");
                    }
                    return value.trim();
                }
                return sqlString(value);
            }
        }
    }

    /** A {@code YYYY-MM-DD} UTC calendar date. */
    private static String isoDate(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
    }

    /** A single-quoted SQL string literal (embedded quotes doubled). */
    private static String sqlString(String v) {
        return "'" + v.replace("'", "''") + "'";
    }
}
