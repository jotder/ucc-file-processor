package com.gamma.pipeline;

import com.gamma.event.EventLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide map of each space's component-registry root, so the <em>static</em> ETL ingest path
 * (and the job / Stage-2 enrichment engines) can load the Decision Rules ({@code decision-rule}
 * components, authored via {@code /decision-rules}) that target the pipeline, job, or enrichment
 * being run — the same global-accessor idiom as
 * {@link com.gamma.acquire.ConnectionRegistry}: the space boot publishes here, the batch worker
 * (which inherits the space MDC) resolves by {@link EventLog#currentSpaceId()}.
 *
 * <p>Rules are re-read from the {@link ComponentStore} on every lookup — a batch is a seconds-scale
 * unit of work and the store is a handful of TOON files, so a rule authored or toggled between two
 * batches takes effect on the next batch without any invalidation protocol.
 *
 * <p>The {@code default} space falls back to {@code -Dassist.write.root} (the legacy single-tenant
 * write root) when nothing was registered, mirroring how the control plane resolves the same
 * registry; a space with no registration and no fallback simply has no rules.
 */
public final class DecisionRules {

    private static final String TYPE = "decision-rule";

    /** {@code space -> registry root}; keyed by {@link EventLog#currentSpaceId()}. */
    private static final Map<String, Path> ROOTS = new ConcurrentHashMap<>();

    private DecisionRules() {
    }

    /** Publish (or replace) a space's component-registry root; {@code null}s are ignored. */
    public static void register(String spaceId, Path registryRoot) {
        if (spaceId != null && registryRoot != null) ROOTS.put(spaceId, registryRoot);
    }

    /** Drop a space's registration (on space deletion), mirroring {@code ConnectionRegistry.forget}. */
    public static void forget(String spaceId) {
        if (spaceId != null) ROOTS.remove(spaceId);
    }

    /** Clear the registry across all spaces (tests). */
    public static void clear() {
        ROOTS.clear();
    }

    /**
     * The enabled Decision Rules targeting a pipeline in the current space, in priority order
     * (lowest number first). Matches {@code targetType: pipeline} rules whose {@code target} equals
     * any of the pipeline's {@code names} (authored name / normalised name) case-insensitively.
     * Empty when the space has no registry.
     */
    public static List<Map<String, Object>> forPipeline(String... names) {
        return forTarget("pipeline", names);
    }

    /**
     * The enabled Decision Rules of the given {@code targetType} whose {@code target} equals any of
     * {@code names} case-insensitively, in priority order — the general form behind
     * {@link #forPipeline}. {@code targetType: job} is how job / Stage-2 enrichment outputs are
     * targeted (matched by job name, and for an enrichment also by the enrichment's own name so the
     * rule holds across every recompute trigger).
     */
    public static List<Map<String, Object>> forTarget(String targetType, String... names) {
        Path root = ROOTS.get(EventLog.currentSpaceId());
        if (root == null && EventLog.DEFAULT_SPACE_ID.equals(EventLog.currentSpaceId())) {
            String wr = System.getProperty("assist.write.root");
            if (wr != null && !wr.isBlank()) root = Path.of(wr.trim()).resolve("registry");
        }
        if (root == null || !Files.isDirectory(root)) return List.of();
        return new ComponentStore(root).list(TYPE).stream()
                .map(ComponentRegistry.Component::content)
                .filter(r -> !"false".equalsIgnoreCase(String.valueOf(r.getOrDefault("enabled", true))))
                .filter(r -> targetType.equals(String.valueOf(r.getOrDefault("targetType", "pipeline"))))
                .filter(r -> targets(r, names))
                .sorted(Comparator.comparingInt(DecisionRules::priorityOf)
                        .thenComparing(r -> String.valueOf(r.get("name"))))
                .toList();
    }

    private static boolean targets(Map<String, Object> rule, String... names) {
        String target = String.valueOf(rule.get("target"));
        for (String n : names) if (n != null && !n.isBlank() && n.equalsIgnoreCase(target)) return true;
        return false;
    }

    private static int priorityOf(Map<String, Object> r) {
        return r.get("priority") instanceof Number n ? n.intValue() : 100;
    }
}
