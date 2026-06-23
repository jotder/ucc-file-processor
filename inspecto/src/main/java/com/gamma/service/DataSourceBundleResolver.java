package com.gamma.service;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.etl.PipelineConfig;
import com.gamma.job.JobConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Resolves a data source (a pipeline) to its cohesive {@link DataSourceBundle} — the pipeline config plus
 * everything it references: the connection profile it binds to, the schema / grammar files it read at parse
 * time, and any job that targets it.
 *
 * <p>Scoped to one space: parsed configs come from that space's {@link SourceService}; the connection / job
 * files are found by scanning that space's {@code config/} tree, because they are addressed by their in-file
 * id ({@code connection.id} / {@code on_pipeline}), not by their filename. Bad / unreadable connection or job
 * files are warned-and-skipped (mirroring boot discovery) so one malformed file never breaks resolution.
 */
public final class DataSourceBundleResolver {

    private static final Logger log = LoggerFactory.getLogger(DataSourceBundleResolver.class);

    private final SourceService service;
    private final Path configDir;

    public DataSourceBundleResolver(SourceService service, Path configDir) {
        this.service   = Objects.requireNonNull(service, "service");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
    }

    /** The data-source ids (pipeline names) that resolve to a bundle in this space, sorted. */
    public List<String> dataSourceIds() {
        return service.pipelines().stream()
                .map(SourceService.PipelineView::name)
                .sorted()
                .toList();
    }

    /**
     * Resolve the bundle for one data source.
     *
     * @param dataSourceId the pipeline name (as listed by {@link #dataSourceIds()})
     * @throws NoSuchElementException if no pipeline with that name exists in this space
     */
    public DataSourceBundle resolve(String dataSourceId) {
        PipelineConfig cfg = service.configFor(dataSourceId)
                .orElseThrow(() -> new NoSuchElementException("no pipeline '" + dataSourceId + "' in this space"));
        Path pipelineFile = service.pathFor(dataSourceId)
                .orElseThrow(() -> new NoSuchElementException("no config file for pipeline '" + dataSourceId + "'"));

        Path connection = cfg.source().hasConnection()
                ? findConnectionFile(cfg.source().connection())
                : null;

        return new DataSourceBundle(
                dataSourceId, pipelineFile, connection,
                cfg.referencedFiles(), findJobsFor(dataSourceId));
    }

    /** The {@code *_connection.toon} whose in-file {@code id} matches {@code connId}, or {@code null} if none. */
    private Path findConnectionFile(String connId) {
        for (Path p : scan("_connection.toon")) {
            try {
                if (connId.equals(ConnectionProfile.load(p).id())) return p;
            } catch (Exception e) {
                log.warn("skipping unreadable connection file {}: {}", p, e.toString());
            }
        }
        log.warn("connection '{}' is referenced by a pipeline but no matching *_connection.toon was found under {}",
                connId, configDir);
        return null;
    }

    /**
     * Every {@code *_job.toon} whose {@code on_pipeline} targets {@code pipelineName}. Matched
     * case-insensitively because the engine lowercases pipeline names on the event bus (a job's
     * {@code on_pipeline} is conventionally the lowercased pipeline name).
     */
    private List<Path> findJobsFor(String pipelineName) {
        List<Path> jobs = new ArrayList<>();
        for (Path p : scan("_job.toon")) {
            try {
                String target = JobConfig.load(p.toString()).onPipeline();
                if (target != null && target.equalsIgnoreCase(pipelineName)) jobs.add(p);
            } catch (Exception e) {
                log.warn("skipping unreadable job file {}: {}", p, e.toString());
            }
        }
        return jobs;
    }

    /** All regular files under the space's config tree whose name ends with {@code suffix}, sorted. */
    private List<Path> scan(String suffix) {
        if (!Files.isDirectory(configDir)) return List.of();
        try (Stream<Path> s = Files.walk(configDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("failed to scan {} for {}: {}", configDir, suffix, e.toString());
            return List.of();
        }
    }
}
