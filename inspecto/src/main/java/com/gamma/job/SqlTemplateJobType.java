package com.gamma.job;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link JobTypeProvider} for the {@code sql.template} Job Type (§15.1, P3b). Its
 * {@link #parameters(JobConfig)} is config-aware: the authored SQL's {@code $name} tokens
 * <i>are</i> its runtime parameter contract (scanned by {@link SqlParamScanner}), surfaced on top of the
 * static config keys so {@code GET /jobs/types/sql.template} drives an authoring form that adapts to the
 * SQL. Constructed with the space's {@code dataDir} (the built-in injection convention).
 */
final class SqlTemplateJobType implements JobTypeProvider {

    static final JobTypeDescriptor DESCRIPTOR = new JobTypeDescriptor("sql.template", "Templated SQL",
            "Runs an authored SQL template over source Datasets and materializes the result as a queryable Dataset.",
            List.of(ParameterDecl.required("sql", ParamType.STRING, "SQL SELECT template; its $name tokens are the runtime parameters"),
                    ParameterDecl.required("sink_dataset", ParamType.STRING, "Output Dataset (store dir under the data root)"),
                    ParameterDecl.optional("sources", ParamType.STRING, null, "CSV of source store names to register as views")),
            List.of("job.dataset.produced"),
            List.of(ArtifactDecl.dataset("output")));

    private final String dataDir;

    SqlTemplateJobType(String dataDir) { this.dataDir = dataDir; }

    @Override public JobTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override public Job create(JobConfig config) { return new SqlTemplateJob(config, dataDir); }

    /** Static config keys plus every {@code $name} scanned from this config's SQL (R3 — the SQL is the contract). */
    @Override
    public List<ParameterDecl> parameters(JobConfig config) {
        List<ParameterDecl> scanned = SqlParamScanner.scan(config.params().get("sql"));
        if (scanned.isEmpty()) return DESCRIPTOR.parameters();
        List<ParameterDecl> all = new ArrayList<>(DESCRIPTOR.parameters());
        all.addAll(scanned);
        return List.copyOf(all);
    }
}
