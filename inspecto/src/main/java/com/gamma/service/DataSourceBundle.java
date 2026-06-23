package com.gamma.service;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The cohesive set of TOON config files that make up one data source within a space: its
 * {@code *_pipeline.toon}, the {@code *_connection.toon} it binds to ({@code source.connection} —
 * {@code null} for a local-filesystem source), the schema / grammar / segment files it referenced at
 * parse time, and any {@code *_job.toon} whose {@code on_pipeline} targets it.
 *
 * <p>This is the granularity for selective export / import and bulk onboarding (Stage 6). It carries
 * only file paths; packaging the bytes is the export side's concern.
 *
 * <p><b>Metadata note:</b> a {@code *_meta.toon} semantic model declares no pipeline reference, so it
 * cannot be linked to a single data source and is intentionally excluded here — it travels with a
 * whole-space export instead.
 *
 * @param id         the data-source id (the pipeline's in-file {@code name})
 * @param pipeline   the {@code *_pipeline.toon} file
 * @param connection the bound {@code *_connection.toon}, or {@code null} for a local source
 * @param schemas    schema / grammar / segment files the pipeline read at parse time (may be empty)
 * @param jobs       {@code *_job.toon} files whose {@code on_pipeline} targets this pipeline (may be empty)
 */
public record DataSourceBundle(
        String id,
        Path pipeline,
        Path connection,
        List<Path> schemas,
        List<Path> jobs) {

    public DataSourceBundle {
        schemas = List.copyOf(schemas);
        jobs    = List.copyOf(jobs);
    }

    /** Every config file in the bundle, de-duplicated, in a stable order: pipeline, connection, schemas, jobs. */
    public List<Path> files() {
        LinkedHashSet<Path> all = new LinkedHashSet<>();
        all.add(pipeline);
        if (connection != null) all.add(connection);
        all.addAll(schemas);
        all.addAll(jobs);
        return List.copyOf(all);
    }
}
