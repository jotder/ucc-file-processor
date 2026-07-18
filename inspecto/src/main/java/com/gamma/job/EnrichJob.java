package com.gamma.job;

import com.gamma.enrich.EnrichmentAuditWriter;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.EnrichmentEngine;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.PartitionOutput;
import com.gamma.service.BatchEventBus;

import java.util.List;

/**
 * An {@link JobType#ENRICH} job: runs a Stage-2 enrichment once (full recompute),
 * writes run-level audit + lineage via {@link EnrichmentAuditWriter} (trigger {@code job}),
 * and publishes a chain {@link BatchEvent} so downstream jobs/enrichments fire — the same
 * contract as {@code EnrichmentService} and the enrichment CLI.
 *
 * <p>Param: {@code config} — path to the enrichment {@code .toon}.
 */
final class EnrichJob implements Job {

    private final JobConfig cfg;
    private final BatchEventBus bus;

    EnrichJob(JobConfig cfg, BatchEventBus bus) {
        this.cfg = cfg;
        this.bus = bus;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "enrich"; }

    @Override
    public JobResult run() throws Exception {
        EnrichmentConfig job = EnrichmentConfig.load(cfg.require("config"));
        String runId = cfg.name().toLowerCase().replace(' ', '_') + "-job-" + EnrichmentAuditWriter.runStamp();
        String start = EnrichmentAuditWriter.now();
        long t0 = System.nanoTime();

        // full recompute; decision rules match this job's name as well as the enrichment's
        EnrichmentEngine.Result res = EnrichmentEngine.runResult(job, null, List.of(),
                List.of(cfg.name()), runId);
        List<PartitionOutput> outs = res.outputs();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        long bytes = outs.stream().mapToLong(PartitionOutput::bytes).sum();
        List<String> parts = outs.stream().map(PartitionOutput::partition).distinct().toList();

        EnrichmentAuditWriter audit =
                new EnrichmentAuditWriter(EnrichmentAuditWriter.auditDir(job), job.name());
        audit.record(new EnrichmentAuditWriter.RunRow(
                runId, job.name(), "job", "job:" + cfg.name(), "full", 0,
                start, EnrichmentAuditWriter.now(), "SUCCESS",
                parts.size(), outs.size(), res.totalRows(), bytes, ms, ""), outs);

        // chain: a successful enrichment is a commit downstream jobs can subscribe to
        bus.publish(new BatchEvent(job.name(), runId, "SUCCESS", parts, res.totalRows(), ms, 0));

        return JobResult.ok(outs.size() + " partition file(s), " + res.totalRows() + " row(s)", ms);
    }
}
