package com.gamma.job;

import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reusable, parameterized job definition (PIP-6), authored as a {@code *_job_template.toon}:
 *
 * <pre>
 * job_template:
 *   name: retention-sweep
 *   params:                     # declared parameters; a blank/absent value means REQUIRED
 *     dir:
 *     retention_days: "30"
 *   job:                        # the job shape; string values may hold ${param} placeholders
 *     type: maintenance
 *     task: cleanup
 *     cron: "0 3 * * *"
 *     dir: ${dir}
 *     retention_days: ${retention_days}
 * </pre>
 *
 * <p>A {@code *_job.toon} instantiates it by reference, supplying its own unique {@code name}, the
 * parameter values, and (optionally) direct overrides of any job key — the ComponentRegistry
 * "reference, override only what's local" idiom:
 *
 * <pre>
 * job:
 *   name: backup-retention
 *   template: retention-sweep
 *   params:
 *     dir: data/backup
 *     retention_days: "90"
 *   cron: "0 4 * * *"           # optional direct override of the template's job block
 * </pre>
 *
 * <p>Resolution happens once, at load ({@code ServiceBootstrap.loadJobs}) — the scheduler and
 * {@link JobService} only ever see plain, fully-resolved {@link JobConfig}s. A reference to an
 * unknown template or a missing required parameter throws here, and the loader's existing
 * warn-and-skip convention keeps the rest of the host booting.
 */
public record JobTemplate(String name, Map<String, String> paramDefaults, Map<String, Object> jobBlock) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    public JobTemplate {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("job_template needs a name");
        name = name.trim();
        paramDefaults = paramDefaults == null ? Map.of() : Map.copyOf(paramDefaults);
        jobBlock = jobBlock == null ? Map.of() : Map.copyOf(jobBlock);
    }

    /** Load one {@code *_job_template.toon} (a {@code job_template { name, params?, job }} block). */
    public static JobTemplate load(String path) throws IOException {
        Map<String, Object> root = ToonHelper.load(path);
        Map<String, Object> t = ToonHelper.requireSection(root, "job_template");
        Map<String, String> defaults = new LinkedHashMap<>();
        if (t.get("params") instanceof Map<?, ?> p)
            // A bare `dir:` (required param, no default) decodes as null OR an empty nested map,
            // depending on what follows it — both mean "declared, no default".
            p.forEach((k, v) -> defaults.put(String.valueOf(k),
                    (v == null || (v instanceof Map<?, ?> m && m.isEmpty())) ? "" : String.valueOf(v)));
        Object job = t.get("job");
        if (!(job instanceof Map))
            throw new IllegalArgumentException("job_template '" + t.get("name") + "' has no 'job' block");
        @SuppressWarnings("unchecked")
        Map<String, Object> jobBlock = (Map<String, Object>) job;
        return new JobTemplate(ToonHelper.require(t, "name", "job_template"), defaults, jobBlock);
    }

    /**
     * Instantiate this template for one referencing {@code job:} block: apply parameter values
     * (instance {@code params:} over the declared defaults) to every {@code ${param}} placeholder,
     * then overlay the instance's direct keys ({@code template} and {@code params} excluded — they
     * are resolution machinery, not job config). Returns the resolved block, ready for
     * {@link JobConfig#fromMap}.
     *
     * @throws IllegalArgumentException on a missing required parameter or an unknown placeholder
     */
    public Map<String, Object> instantiate(Map<String, Object> instanceJob) {
        String jobName = String.valueOf(instanceJob.getOrDefault("name", "?"));

        // Effective parameters: declared defaults, overridden by the instance's params block.
        Map<String, String> values = new LinkedHashMap<>(paramDefaults);
        if (instanceJob.get("params") instanceof Map<?, ?> p)
            p.forEach((k, v) -> values.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        for (Map.Entry<String, String> e : values.entrySet())
            if (e.getValue() == null || e.getValue().isBlank())
                throw new IllegalArgumentException("Job '" + jobName + "' (template '" + name
                        + "') is missing required param '" + e.getKey() + "'");

        // Substitute placeholders through the template's job block, then overlay the instance keys.
        Map<String, Object> resolved = new LinkedHashMap<>();
        jobBlock.forEach((k, v) -> resolved.put(k, substitute(v, values, jobName)));
        for (Map.Entry<String, Object> e : instanceJob.entrySet()) {
            if ("template".equals(e.getKey()) || "params".equals(e.getKey())) continue;
            resolved.put(e.getKey(), e.getValue());   // instance wins — override only what's local
        }
        return resolved;
    }

    /** Recursive {@code ${param}} substitution over strings, maps and lists (other values pass through). */
    private Object substitute(Object v, Map<String, String> values, String jobName) {
        return switch (v) {
            case String s -> {
                Matcher m = PLACEHOLDER.matcher(s);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String val = values.get(m.group(1));
                    if (val == null)
                        throw new IllegalArgumentException("Job '" + jobName + "' (template '" + name
                                + "'): placeholder ${" + m.group(1) + "} is not a declared param");
                    m.appendReplacement(sb, Matcher.quoteReplacement(val));
                }
                m.appendTail(sb);
                yield sb.toString();
            }
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, val) -> out.put(String.valueOf(k), substitute(val, values, jobName)));
                yield out;
            }
            case List<?> list -> list.stream().map(item -> substitute(item, values, jobName)).toList();
            case null, default -> v;
        };
    }
}
