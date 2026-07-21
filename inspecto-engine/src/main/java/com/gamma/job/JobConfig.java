package com.gamma.job;

import com.gamma.api.PublicApi;
import com.gamma.util.CronExpression;
import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for one config-driven {@link Job}, loaded from a {@code *_job.toon} file.
 * A job declares <em>what</em> kind of work it does ({@link JobType}), <em>when</em> it
 * runs (a cron expression and/or an upstream pipeline event), and the type-specific
 * parameters it needs.
 *
 * <pre>
 * job:
 *   name: nightly-rollup
 *   type: enrich                 # enrich | report | maintenance (ingest is pipeline-only, §3.8)
 *   cron: "0 2 * * *"            # optional — calendar schedule (5 or 6 cron fields)
 *   on_pipeline: UPSTREAM        # optional — also run when this pipeline commits a batch
 *   enabled: true                # optional — default true
 *   catch_up: false              # optional — on startup, run once if a cron fire was missed (T26)
 *   config: config/events_enrich.toon     # type-specific params follow…
 * </pre>
 *
 * <p>Recognised top-level keys are {@code name}, {@code type}, {@code cron},
 * {@code on_pipeline}, {@code enabled} and {@code catch_up}; every other key in the {@code job}
 * section is captured verbatim into {@link #params()} for the job implementation to read (e.g.
 * {@code config}, {@code scope}, {@code task}, {@code dir}, {@code retention_days}).
 *
 * @param name       unique job name
 * @param type       the kind of work
 * @param cron       cron expression, or {@code null}/blank for no schedule
 * @param onPipeline upstream pipeline/job name whose batch-commit triggers this job, or {@code null}
 * @param enabled    whether the scheduler should arm this job
 * @param catchUp    whether to run once on startup when a scheduled fire was missed while down (T26)
 * @param params     type-specific parameters (all values as strings)
 */
@PublicApi(since = "4.0.0")
public record JobConfig(String name, String type, String cron, String onPipeline,
                        boolean enabled, boolean catchUp, Map<String, String> params,
                        String onSignal, String when,
                        Map<String, String> args, Map<String, String> bind) {

    /** Null-guard the trigger maps so {@link #args()}/{@link #bind()} accessors never return null (P3a-2). */
    public JobConfig {
        args = args == null ? Map.of() : args;
        bind = bind == null ? Map.of() : bind;
    }

    /**
     * P2b 9-arg String call site ({@code fromMap} pre-P3a-2, and any code that doesn't set trigger
     * {@code args}/{@code bind}). No static trigger args, no signal bindings.
     */
    public JobConfig(String name, String type, String cron, String onPipeline,
                     boolean enabled, boolean catchUp, Map<String, String> params,
                     String onSignal, String when) {
        this(name, type, cron, onPipeline, enabled, catchUp, params, onSignal, when, Map.of(), Map.of());
    }

    /**
     * Back-compat constructor (pre-P1c 7-arg call sites, {@link JobType} typed): no {@code on_signal}
     * trigger and no {@code when} guard. The enum maps to its lowercased id.
     * @deprecated since 5.x — pass the type id string; {@link JobType} is deprecated (P2b).
     */
    @Deprecated(since = "5.x")
    public JobConfig(String name, JobType type, String cron, String onPipeline,
                     boolean enabled, boolean catchUp, Map<String, String> params) {
        this(name, idOf(type), cron, onPipeline, enabled, catchUp, params, null, null, Map.of(), Map.of());
    }

    /**
     * Back-compat constructor (P1c 9-arg call sites, {@link JobType} typed).
     * @deprecated since 5.x — pass the type id string; {@link JobType} is deprecated (P2b).
     */
    @Deprecated(since = "5.x")
    public JobConfig(String name, JobType type, String cron, String onPipeline,
                     boolean enabled, boolean catchUp, Map<String, String> params,
                     String onSignal, String when) {
        this(name, idOf(type), cron, onPipeline, enabled, catchUp, params, onSignal, when, Map.of(), Map.of());
    }

    private static String idOf(JobType type) {
        return type == null ? null : type.name().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean hasCron()   { return cron != null && !cron.isBlank(); }
    public boolean hasEvent()  { return onPipeline != null && !onPipeline.isBlank(); }
    public boolean hasSignal() { return onSignal != null && !onSignal.isBlank(); }
    public boolean hasWhen()   { return when != null && !when.isBlank(); }
    public boolean hasBind()   { return !bind.isEmpty(); }

    /** A required param, or an {@link IllegalArgumentException} naming the job. */
    public String require(String key) {
        String v = params.get(key);
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("Job '" + name + "' (" + type
                    + ") requires param '" + key + "'");
        return v;
    }

    /** An optional param with a fallback. */
    public String opt(String key, String fallback) {
        String v = params.get(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** Parse the cron field into a validated {@link CronExpression} (caller checks {@link #hasCron()}). */
    public CronExpression cronExpression() {
        return CronExpression.parse(cron);
    }

    // ── factory ────────────────────────────────────────────────────────────────

    public static JobConfig load(String configPath) throws IOException {
        return fromMap(ToonHelper.load(configPath));
    }

    /**
     * Build a {@code JobConfig} from an already-decoded config map — a <b>pure</b> parse (no file
     * I/O). The cron expression is still validated eagerly so a bad expression fails here rather than
     * at first fire.
     */
    public static JobConfig fromMap(Map<String, Object> raw) {
        Map<String, Object> job = ToonHelper.requireSection(raw, "job");

        String name = ToonHelper.require(job, "name", "job");
        // P2b: type is an open registry id (was the JobType enum). Required; normalized to a lowercase id.
        String type = ToonHelper.require(job, "type", "job").trim().toLowerCase(java.util.Locale.ROOT);
        String cron = ToonHelper.opt(job, "cron", null);
        String onPipeline = ToonHelper.opt(job, "on_pipeline", null);
        String onSignal = ToonHelper.opt(job, "on_signal", null);   // P1c: signal-type trigger (exact or prefix.*)
        String when = ToonHelper.opt(job, "when", null);            // P1c: guard expression over $signal.*
        boolean enabled = !"false".equalsIgnoreCase(ToonHelper.opt(job, "enabled", "true"));
        boolean catchUp = "true".equalsIgnoreCase(ToonHelper.opt(job, "catch_up", "false"));

        // validate the cron eagerly so a bad expression fails at load, not at first fire
        if (cron != null && !cron.isBlank()) CronExpression.parse(cron);

        // P3a-2: static trigger args (layer 1) and signal bindings (layer 2, values are $signal.<field>
        // expressions) — nested maps, distinct from the type-specific params: block (layer 3).
        Map<String, String> args = subMap(job.get("args"));
        Map<String, String> bind = subMap(job.get("bind"));

        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : job.entrySet()) {
            switch (e.getKey()) {
                case "name", "type", "cron", "on_pipeline", "on_signal", "when", "enabled", "catch_up",
                     "args", "bind" -> { /* known keys */ }
                default -> { if (e.getValue() != null) params.put(e.getKey(), e.getValue().toString()); }
            }
        }
        return new JobConfig(name, type, cron, onPipeline, enabled, catchUp, params, onSignal, when, args, bind);
    }

    /** Reverse of {@link #fromMap} — the {@code job:} section content this config writes back to TOON
     *  (job CRUD write endpoints). Key set mirrors {@link #fromMap}'s recognised keys, then {@link #params()}
     *  verbatim; omits blank/default-valued optional fields so a round-tripped file stays minimal. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        if (hasCron()) m.put("cron", cron);
        if (hasEvent()) m.put("on_pipeline", onPipeline);
        if (hasSignal()) m.put("on_signal", onSignal);
        if (hasWhen()) m.put("when", when);
        m.put("enabled", enabled);
        if (catchUp) m.put("catch_up", true);
        if (!args.isEmpty()) m.put("args", new LinkedHashMap<>(args));
        if (!bind.isEmpty()) m.put("bind", new LinkedHashMap<>(bind));
        params.forEach(m::put);
        return m;
    }

    /** Coerce a decoded nested section ({@code args:}/{@code bind:}) into a string-valued map; empty when absent. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> subMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<String, Object>) m).entrySet())
            if (e.getValue() != null) out.put(String.valueOf(e.getKey()), e.getValue().toString());
        return out;
    }
}
