package com.gamma.job;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The open registry of {@link JobTypeProvider}s that replaced the compiled-in {@link JobType} switch
 * in {@code JobService.build()} (P0, {@code docs/job-framework-design.md} §6.1). Keyed by type id;
 * the four built-ins register under their lowercased enum names ({@code enrich} / {@code report} /
 * {@code maintenance} / {@code pipeline}) so existing {@code *_job.toon} files load unchanged.
 */
final class JobTypeRegistry {

    private final Map<String, JobTypeProvider> providers = new LinkedHashMap<>();

    void register(JobTypeProvider provider) {
        if (providers.putIfAbsent(provider.id(), provider) != null)
            throw new IllegalStateException("duplicate job type id '" + provider.id() + "'");
    }

    /** Build the {@link Job} for an authored config; throws if the type id is unknown. */
    Job create(String id, JobConfig config) {
        JobTypeProvider p = providers.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        if (p == null)
            throw new IllegalArgumentException("unknown job type '" + id
                    + "' (registered: " + providers.keySet() + ")");
        return p.create(config);
    }

    boolean has(String id) { return id != null && providers.containsKey(id.toLowerCase(Locale.ROOT)); }

    Set<String> ids() { return Set.copyOf(providers.keySet()); }
}
