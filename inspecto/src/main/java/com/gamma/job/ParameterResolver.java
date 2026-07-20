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
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gamma.util.DottedPath;

/**
 * Resolves a Job's declared {@link ParameterDecl}s to concrete values for one Run (job-framework §7.2,
 * the parameter slice of P1b/P3a + P3a-2). For each declaration the first hit wins across the layers:
 * <ol>
 *   <li>trigger {@code args} — explicit values on this firing (manual {@code POST} body {@code params:}
 *       or a Trigger's static {@code args:} block),</li>
 *   <li>signal {@code bind} — the Trigger's {@code bind:} map, each value a {@code $}-expression
 *       (typically {@code $signal.<field>}) evaluated against the firing Signal's payload,</li>
 *   <li>authored {@code config} (the {@code *_job.toon} {@code params:} block),</li>
 *   <li>{@code deduce} — the declaration's {@code $}-expression against the built-in context (§7.3),</li>
 *   <li>{@code defaultValue} — the literal fallback.</li>
 * </ol>
 * A {@code required} parameter still unresolved goes into {@link Resolution#missingRequired()} so the
 * framework can fail the Run <b>REJECTED</b> before any user code runs (§7.2, fail-closed). Likewise
 * (2026-07-20), a resolved value that doesn't parse as its declared {@link ParamType} goes into
 * {@link Resolution#invalidType()} instead of {@code resolved()} — a required INTEGER parameter bound
 * from {@code $signal.foo} to a non-numeric string is REJECTED here rather than throwing an uncaught
 * {@code NumberFormatException} deep inside a Job's {@code run(ctx)} once it tries to parse the string
 * itself ({@link ParamType} was previously form-gen/descriptor metadata only, never enforced).
 *
 * <p>The {@code $upstream(<job>).artifact(<name>).<attr>} token (§10) resolves against recorded Run
 * Artifacts. This is a fresh, minimal evaluator; consolidating it with {@code com.gamma.query.Parameters}
 * (SQL-literal output, a different token set) and {@link WhenGuard}'s {@code $signal} evaluator is
 * deliberate future work.
 */
final class ParameterResolver {

    private ParameterResolver() {}

    /** The built-in {@code $}-context for deduction (§7.3): this Run's identity/timing, the success
     *  watermark, an {@code (job, artifactName)} → latest {@link RunArtifact} lookup for {@code $upstream},
     *  and the firing Signal's payload for {@code $signal.<field>} (empty for cron/manual fires). */
    record Context(String runId, Instant fireTime, String actor, ZoneId zone,
                   Supplier<Optional<LocalDateTime>> lastSuccess,
                   BiFunction<String, String, Optional<RunArtifact>> upstream,
                   Map<String, Object> signalPayload) {}

    /** Outcome: the resolved values, any {@code required} names that stayed unresolved, and any name whose
     *  resolved value didn't parse as its declared {@link ParamType} (both ⇒ REJECTED). */
    record Resolution(Map<String, String> resolved, List<String> missingRequired, List<String> invalidType) {}

    private static final Pattern DATE_FN = Pattern.compile("\\$(day|month)\\(\\s*(-?\\d+)\\s*\\)");
    private static final Pattern UPSTREAM =
            Pattern.compile("\\$upstream\\(([^)]+)\\)\\.artifact\\(([^)]+)\\)\\.(\\w+)");

    static Resolution resolve(List<ParameterDecl> decls, Map<String, String> args,
                              Map<String, String> bind, Map<String, String> config, Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> invalidType = new ArrayList<>();
        for (ParameterDecl d : decls) {
            String v = value(d, args, bind, config, ctx);
            if (v == null) {
                if (d.required()) missing.add(d.name());
                continue;
            }
            if (!matchesType(d.type(), v)) {
                invalidType.add(d.name() + " (expected " + d.type() + ", got '" + v + "')");
                continue;
            }
            out.put(d.name(), v);
        }
        return new Resolution(Map.copyOf(out), List.copyOf(missing), List.copyOf(invalidType));
    }

    /** Whether {@code v} parses as {@code type} (§7.1). {@code STRING}/{@code DATASET_REF} accept any
     *  non-blank string — a dataset reference's *existence* is a different, later concern, not a parse
     *  format. {@code null}/blank never reaches here (see {@link #value}, which already excludes it). */
    private static boolean matchesType(ParamType type, String v) {
        try {
            switch (type) {
                case INTEGER: Long.parseLong(v); return true;
                case DECIMAL: Double.parseDouble(v); return true;
                case BOOLEAN: return "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v);
                case DATE: LocalDate.parse(v); return true;
                case INSTANT: Instant.parse(v); return true;
                case STRING:
                case DATASET_REF:
                default: return true;
            }
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** First hit of: trigger args → signal bind → authored config → deduce → default. {@code null} ⇒ unresolved. */
    private static String value(ParameterDecl d, Map<String, String> args,
                                Map<String, String> bind, Map<String, String> config, Context ctx) {
        String a = args.get(d.name());
        if (a != null && !a.isBlank()) return a;
        String b = bind.get(d.name());
        if (b != null && !b.isBlank()) {
            String bv = deduce(b.trim(), ctx);
            if (bv != null) return bv;
        }
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
                if (expr.startsWith("$signal.")) {
                    Object v = DottedPath.resolve(ctx.signalPayload(), expr.substring("$signal.".length()));
                    return v == null ? null : String.valueOf(v);
                }
                Matcher m = DATE_FN.matcher(expr);
                if (m.matches()) {
                    LocalDate base = LocalDate.ofInstant(ctx.fireTime(), ctx.zone());
                    int n = Integer.parseInt(m.group(2));
                    return ("day".equals(m.group(1)) ? base.plusDays(n) : base.plusMonths(n)).toString();
                }
                Matcher u = UPSTREAM.matcher(expr);
                if (u.matches()) return upstreamAttr(ctx, u.group(1).trim(), u.group(2).trim(), u.group(3));
                return null;
        }
    }

    /** {@code $upstream(<job>).artifact(<name>).<attr>} — an attr of a predecessor's latest artifact (§10). */
    private static String upstreamAttr(Context ctx, String job, String artifact, String attr) {
        return ctx.upstream().apply(job, artifact).map(a -> switch (attr) {
            case "ref"        -> a.ref();
            case "rows"       -> String.valueOf(a.rows());
            case "bytes"      -> String.valueOf(a.bytes());
            case "watermark"  -> a.watermark();
            case "time_range" -> a.timeRange();
            default           -> null;
        }).orElse(null);
    }
}
