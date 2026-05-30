package com.gamma.job;

import com.gamma.etl.BatchEvent;
import com.gamma.inspector.MultiSourceProcessor;
import com.gamma.service.BatchEventBus;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link JobType#INGEST} job: runs one Stage-1 pipeline once via
 * {@link MultiSourceProcessor#runAll}, feeding its commit events to the shared bus so
 * enrichment, metrics and downstream chains react exactly as they do for a poll cycle.
 *
 * <p>Param: {@code config} — path to the pipeline {@code .toon}.
 */
final class IngestJob implements Job {

    private final JobConfig cfg;
    private final Consumer<BatchEvent> sink;

    IngestJob(JobConfig cfg, BatchEventBus bus) {
        this.cfg  = cfg;
        this.sink = bus.sink();
    }

    @Override public String name() { return cfg.name(); }
    @Override public JobType type() { return JobType.INGEST; }

    @Override
    public JobResult run() {
        Path config = Path.of(cfg.require("config"));
        long t0 = System.nanoTime();
        MultiSourceProcessor.RunResult r = MultiSourceProcessor.runAll(List.of(config), 1, sink);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        String msg = r.total() + " source(s) run, " + r.failed() + " failed";
        return r.failed() > 0 ? JobResult.failed(msg, ms) : JobResult.ok(msg, ms);
    }
}
