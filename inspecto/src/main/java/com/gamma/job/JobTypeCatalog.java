package com.gamma.job;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one type catalog spanning every registered Job Type's declared {@link JobTypeDescriptor#emits()}
 * (event-signal-backbone-plan §4.3, S1). Promotes {@code emits} from descriptive-only to enforceable:
 * {@link #flagUncatalogued(String, String)} is the audit check {@code MaintenanceJob.schedulerAudit}
 * (and any other producer-side auditor) can call to catch a Job Type emitting a signal type it never
 * declared — the bug that let {@code report} declare {@code emits=[]} while firing {@code REPORT_READY}
 * go unnoticed.
 *
 * <p>Deliberately minimal for S1: no schema/category/since versioning (§4.3's aspirational fields) —
 * just a lookup from job-type id to its own declared type list, and one enforcement check over it.
 */
public final class JobTypeCatalog {

    private final Map<String, JobTypeDescriptor> byId;

    private JobTypeCatalog(Map<String, JobTypeDescriptor> byId) {
        this.byId = byId;
    }

    /** Build the catalog from every registered Job Type's descriptor (e.g. {@code JobService.jobTypes()}). */
    public static JobTypeCatalog of(Collection<JobTypeDescriptor> descriptors) {
        Map<String, JobTypeDescriptor> m = new LinkedHashMap<>();
        for (JobTypeDescriptor d : descriptors) m.put(d.id(), d);
        return new JobTypeCatalog(m);
    }

    /** The declared descriptor for a Job Type id, if catalogued. */
    public Optional<JobTypeDescriptor> find(String jobTypeId) {
        return Optional.ofNullable(byId.get(jobTypeId));
    }

    /** Does {@code jobTypeId}'s own declared {@code emits} list include {@code signalType}? An unknown
     *  job type id is treated as undeclared. */
    public boolean isDeclared(String jobTypeId, String signalType) {
        JobTypeDescriptor d = byId.get(jobTypeId);
        return d != null && d.emits().contains(signalType);
    }

    /** Audit check: given a Job Type observed emitting {@code signalType}, flag it when that type is
     *  not in the type's own declared {@code emits} — an under-declaration, exactly like {@code report}'s
     *  {@code emits=[]} vs. its real {@code REPORT_READY} emission. Empty when the emission is declared. */
    public Optional<String> flagUncatalogued(String jobTypeId, String signalType) {
        if (isDeclared(jobTypeId, signalType)) return Optional.empty();
        return Optional.of("job type '" + jobTypeId + "' emitted uncatalogued signal type '" + signalType
                + "' (not declared in its own emits)");
    }
}
