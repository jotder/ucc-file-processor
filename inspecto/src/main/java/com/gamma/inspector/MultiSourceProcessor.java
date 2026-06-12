package com.gamma.inspector;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Top-level orchestrator that runs <b>multiple data sources</b> (each its own
 * {@code pipeline.toon}) concurrently in one JVM. This is the outer layer of the
 * framework's parallelism model; {@link SourceProcessor#run(PipelineConfig)} is
 * the per-source unit it composes.
 *
 * <h3>Concurrency model</h3>
 * Sources run on a virtual-thread executor bounded by a {@link Semaphore}, so a
 * source blocked on I/O parks cheaply while at most {@code sources.max} run at
 * once. Each source independently bounds its own batch concurrency
 * ({@code processing.threads}) and per-batch DuckDB threads
 * ({@code processing.duckdb_threads}). The three caps multiply, so total worker
 * pressure ≈ {@code sources.max × threads × duckdb_threads} — size them together
 * for the host.
 *
 * <h3>CLI</h3>
 * <pre>
 *   java -cp file-processor.jar com.gamma.inspector.MultiSourceProcessor \
 *        [-Dsources.max=N] &lt;path&gt; [&lt;path&gt; ...]
 * </pre>
 * Each {@code path} is either a {@code *_pipeline.toon} file or a directory, which
 * is searched (recursively) for {@code *_pipeline.toon} files. {@code sources.max}
 * defaults to the number of resolved sources (all in parallel). Exits non-zero if
 * any source fails so a wrapper/cron can detect partial-failure runs.
 *
 * <p>Unlike {@link SourceProcessor#main}, this does not reconfigure the global
 * {@code LogSetup} tee per source (that would clobber across concurrent sources);
 * operational output flows through SLF4J, where each line carries its logger and
 * the relevant batch/source id.
 */
@PublicApi(since = "1.6.0")
public final class MultiSourceProcessor {

    private static final Logger log = LoggerFactory.getLogger(MultiSourceProcessor.class);

    private MultiSourceProcessor() {}

    /** Outcome of a multi-source run. */
    public record RunResult(int total, int failed) {
        public int succeeded() { return total - failed; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: MultiSourceProcessor [-Dsources.max=N] <pipeline.toon | dir> [more ...]");
            System.exit(1);
        }
        List<Path> configs = resolveConfigs(args);
        if (configs.isEmpty()) {
            System.err.println("No *_pipeline.toon files found in: " + String.join(", ", args));
            System.exit(1);
        }
        int maxConcurrent = Integer.getInteger("sources.max", configs.size());
        log.info("Running {} source(s), up to {} concurrently", configs.size(), maxConcurrent);

        RunResult result = runAll(configs, maxConcurrent);
        log.info("Multi-source run complete: {} succeeded, {} failed (of {})",
                result.succeeded(), result.failed(), result.total());
        if (result.failed() > 0) {
            System.err.println("[FAIL] " + result.failed() + " of " + result.total() + " source(s) failed");
            System.exit(2);
        }
    }

    /**
     * Run every config concurrently, bounded by {@code maxConcurrent} permits.
     * Each source is isolated: a failure (config load error or batch failures)
     * is logged and counted but never aborts the others.
     *
     * @return a {@link RunResult} with total and failed source counts
     */
    public static RunResult runAll(List<Path> configs, int maxConcurrent) {
        return runAll(configs, maxConcurrent, null);
    }

    /**
     * As {@link #runAll(List, int)}, but emits a {@link com.gamma.etl.BatchEvent} to
     * {@code onCommit} after each SUCCESS batch (used by the service layer to feed its
     * event bus). {@code onCommit} may be {@code null}. Each config is loaded fresh
     * per call, so every cycle is a new run with its own run timestamp.
     */
    public static RunResult runAll(List<Path> configs, int maxConcurrent,
                                   java.util.function.Consumer<com.gamma.etl.BatchEvent> onCommit) {
        Semaphore permits   = new Semaphore(Math.max(1, maxConcurrent));
        AtomicInteger failed = new AtomicInteger();

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Path cfgPath : configs) {
                futures.add(exec.submit(() -> {
                    permits.acquire();
                    try {
                        PipelineConfig cfg = PipelineConfig.load(cfgPath.toString());
                        SourceProcessor.run(cfg, onCommit);
                        log.info("Source '{}' completed", cfg.identity().pipelineName());
                    } catch (SourceProcessor.BatchProcessingException e) {
                        failed.incrementAndGet();
                        log.error("Source {} had batch failures: {}", cfgPath, e.getMessage());
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        log.error("Source {} failed to run", cfgPath, e);
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.error("Source task error", e);
                }
            }
        }
        return new RunResult(configs.size(), failed.get());
    }

    /**
     * Expand CLI args into a deterministic, de-duplicated list of pipeline configs.
     * A directory arg is searched recursively for {@code *_pipeline.toon}; a file
     * arg is taken as-is. Missing paths are warned and skipped.
     */
    public static List<Path> resolveConfigs(String[] args) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String a : args) {
            Path p = Paths.get(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> w = Files.walk(p)) {
                    w.filter(Files::isRegularFile)
                     .filter(f -> f.getFileName().toString().endsWith("_pipeline.toon"))
                     .sorted()
                     .forEach(out::add);
                }
            } else if (Files.isRegularFile(p)) {
                out.add(p);
            } else {
                log.warn("Path not found, skipping: {}", a);
            }
        }
        return out;
    }
}
