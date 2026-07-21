package com.gamma.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The open registry of {@link JobTypeProvider}s that replaced the compiled-in {@link JobType} switch
 * in {@code JobService.build()} (P0, {@code docs/job-framework-design.md} §6.1). Keyed by type id;
 * the four built-ins register under their lowercased enum names ({@code enrich} / {@code report} /
 * {@code maintenance} / {@code pipeline}) so existing {@code *_job.toon} files load unchanged.
 *
 * <p>Every provider carries an <em>owner</em> tag: {@code null} for the permanent built-ins and
 * classpath ({@code ServiceLoader}) providers, or a Job Pack's key for hot-deployed types (P2c). Only
 * pack-owned types can be {@linkplain #deregister(String) deregistered} — a pack can never displace a
 * built-in (its id collides and is rejected at {@link #register(JobTypeProvider, String)}).
 */
final class JobTypeRegistry {

    private final Map<String, JobTypeProvider> providers = new LinkedHashMap<>();
    private final Map<String, String> owners = new LinkedHashMap<>();   // id -> owner (null = permanent)

    /** Register a permanent (built-in / classpath) provider — never deregistered. */
    void register(JobTypeProvider provider) {
        register(provider, null);
    }

    /** Register a provider owned by {@code owner} (a Job Pack key, or {@code null} for permanent). */
    void register(JobTypeProvider provider, String owner) {
        if (providers.putIfAbsent(provider.id(), provider) != null)
            throw new IllegalStateException("duplicate job type id '" + provider.id() + "'");
        owners.put(provider.id(), owner);
    }

    /** Remove every type owned by {@code owner} (Job Pack unload/reload); returns the ids removed. */
    List<String> deregister(String owner) {
        List<String> removed = new ArrayList<>();
        for (var it = owners.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (java.util.Objects.equals(owner, e.getValue())) {
                providers.remove(e.getKey());
                removed.add(e.getKey());
                it.remove();
            }
        }
        return removed;
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

    /** The Job Pack that owns {@code id}'s provider, or empty for a built-in/permanent registration
     *  (or an unknown id) — lets a Run pin its owning pack's classloader open for its duration
     *  (Job Pack in-flight-Run quiesce, §12.2). */
    Optional<String> ownerOf(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(owners.get(id.toLowerCase(Locale.ROOT)));
    }

    Set<String> ids() { return Set.copyOf(providers.keySet()); }

    /** Every registered type's descriptor, in registration order (R3 / {@code GET /jobs/types}). */
    List<JobTypeDescriptor> descriptors() {
        return providers.values().stream().map(JobTypeProvider::descriptor).toList();
    }

    /** One type's descriptor by id (case-insensitive), if registered. */
    Optional<JobTypeDescriptor> descriptor(String id) {
        JobTypeProvider p = providers.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        return Optional.ofNullable(p).map(JobTypeProvider::descriptor);
    }

    /** The parameters a type requires for one authored config (R3) — config-aware where the provider
     *  overrides {@link JobTypeProvider#parameters(JobConfig)}; empty for an unknown id. */
    List<ParameterDecl> parameters(String id, JobConfig config) {
        JobTypeProvider p = providers.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        return p == null ? List.of() : p.parameters(config);
    }
}
