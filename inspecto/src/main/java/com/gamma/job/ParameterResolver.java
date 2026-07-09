package com.gamma.job;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a Job's declared {@link ParameterDecl}s to concrete values for one Run (job-framework §7,
 * the "P3a" slice of P1b). For each declaration the first hit wins across the layers reachable today:
 * <ol>
 *   <li>authored {@code config} (the {@code *_job.toon} {@code params:} block),</li>
 *   <li>{@code deduce} — the declaration's {@code $}-expression against the built-in context (§7.3),</li>
 *   <li>{@code defaultValue} — the literal fallback.</li>
 * </ol>
 * A {@code required} parameter still unresolved goes into {@link Resolution#missingRequired()} so the
 * framework can fail the Run <b>REJECTED</b> before any user code runs (§7.2, fail-closed).
 *
 * <p>Not yet wired (they need run-path plumbing that arrives later): trigger {@code args} (layer 1) and
 * signal {@code bind} (layer 2), and therefore the {@code $signal.<field>} and {@code $upstream(...)}
 * tokens. This is a fresh, minimal evaluator; consolidating it with {@code com.gamma.query.Parameters}
 * (SQL-literal output, a different token set) and {@link WhenGuard}'s {@code $signal} evaluator is
 * deliberate future work.
 */
final class ParameterResolver {

    private ParameterResolver() {}

    /** The built-in {@code $}-context for deduction (§7.3): this Run's identity/timing + the success watermark. */
    record Context(String runId, Instant fireTime, String actor, ZoneId zone,
                   Supplier<Optional<LocalDateTime>> lastSuccess) {}

    /** Outcome: the resolved values, and any {@code required} names that stayed unresolved (⇒ REJECTED). */
    record Resolution(Map<String, String> resolved, List<String> missingRequired) {}

    private static final Pattern DATE_FN = Pattern.compile("\\$(day|month)\\(\\s*(-?\\d+)\\s*\\)");

    static Resolution resolve(List<ParameterDecl> decls, Map<String, String> config, Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (ParameterDecl d : decls) {
            String v = value(d, config, ctx);
            if (v == null) {
                if (d.required()) missing.add(d.name());
                continue;
            }
            out.put(d.name(), v);
        }
        return new Resolution(Map.copyOf(out), List.copyOf(missing));
    }

    /** First hit of: authored config → deduce → default. {@code null} ⇒ unresolved. */
    private static String value(ParameterDecl d, Map<String, String> config, Context ctx) {
        String c = config.get(d.name());
        if (c != null && !c.isBlank()) return c;
        if (d.deduce() != null && !d.deduce().isBlank()) {
            String dv = deduce(d.deduce().trim(), ctx);
            if (dv != null) return dv;
        }
        return d.defaultValue();   // may be null
    }

    /** Evaluate one {@code $}-expression against the context; {@code null} when the token is unknown/unavailable. */
    static String deduce(String expr, Context ctx) {
        switch (expr) {
            case "$today":         return LocalDate.ofInstant(ctx.fireTime(), ctx.zone()).toString();
            case "$now":           return ctx.fireTime().toString();
            case "$run.id":        return ctx.runId();
            case "$run.fire_time": return ctx.fireTime().toString();
            case "$run.actor":     return ctx.actor();
            case "$job.last_success_time":
                return ctx.lastSuccess().get()
                        .map(t -> t.atZone(ctx.zone()).toInstant().toString()).orElse(null);
            default:
                Matcher m = DATE_FN.matcher(expr);
                if (m.matches()) {
                    LocalDate base = LocalDate.ofInstant(ctx.fireTime(), ctx.zone());
                    int n = Integer.parseInt(m.group(2));
                    return ("day".equals(m.group(1)) ? base.plusDays(n) : base.plusMonths(n)).toString();
                }
                return null;
        }
    }
}
