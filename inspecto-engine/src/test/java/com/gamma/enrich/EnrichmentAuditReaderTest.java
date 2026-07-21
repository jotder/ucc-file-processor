package com.gamma.enrich;

import com.gamma.etl.PartitionOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips the Stage-2 audit: what {@link EnrichmentAuditWriter} persists,
 * {@link EnrichmentAuditReader} must read back as ordered header→value maps — including
 * the per-run lineage filter and the empty-ledger (job never ran) case.
 */
class EnrichmentAuditReaderTest {

    private static EnrichmentAuditWriter.RunRow run(String runId, String job, String status,
                                                    int outFiles, long rows, long bytes) {
        return new EnrichmentAuditWriter.RunRow(runId, job, "event", "event:UP", "full",
                1, "2026-05-30 10:00:00", "2026-05-30 10:00:02", status,
                1, outFiles, rows, bytes, 1500L, status.equals("FAILED") ? "boom" : "");
    }

    @Test
    void readsBackRunsAndLineageInAppendOrder(@TempDir Path dir) {
        EnrichmentAuditWriter w = new EnrichmentAuditWriter(dir.toString(), "EVENTS_KPI");
        w.record(run("r1", "EVENTS_KPI", "SUCCESS", 2, 10, 4096),
                List.of(new PartitionOutput("day=03", "out/day=03/a.parquet", 2048),
                        new PartitionOutput("day=03", "out/day=03/b.parquet", 2048)));
        w.record(run("r2", "EVENTS_KPI", "SUCCESS", 1, 5, 1024),
                List.of(new PartitionOutput("day=04", "out/day=04/c.parquet", 1024)));

        EnrichmentAuditReader r = new EnrichmentAuditReader(dir.toString(), "EVENTS_KPI");

        List<Map<String, String>> runs = r.runs();
        assertEquals(2, runs.size(), "both runs read back");
        assertEquals("r1", runs.get(0).get("run_id"), "append order is chronological");
        assertEquals("r2", runs.get(1).get("run_id"));
        assertEquals("SUCCESS", runs.get(0).get("status"));
        assertEquals("10", runs.get(0).get("total_output_rows"));
        assertEquals("event:UP", runs.get(0).get("reason"), "quoted field parses cleanly");

        // lineage: 2 files for r1 + 1 for r2
        assertEquals(3, r.lineage(null).size());
        List<Map<String, String>> r1 = r.lineage("r1");
        assertEquals(2, r1.size(), "lineage filtered to one run");
        assertTrue(r1.stream().allMatch(m -> "r1".equals(m.get("run_id"))));
        assertEquals("out/day=03/a.parquet", r1.get(0).get("output_file"));
    }

    @Test
    void missingLedgersReadAsEmpty(@TempDir Path dir) {
        EnrichmentAuditReader r = new EnrichmentAuditReader(dir.toString(), "NEVER_RAN");
        assertTrue(r.runs().isEmpty(), "no ledger → empty, not an error");
        assertTrue(r.lineage(null).isEmpty());
    }

    @Test
    void forConfigUsesTheWriterAuditConvention(@TempDir Path dir) {
        // forConfig must resolve the same _audit dir + <job> file names the writer uses,
        // so a reader opened from a config sees what the orchestrator wrote.
        Path out = dir.resolve("reports/kpi");
        EnrichmentConfig cfg = new EnrichmentConfig("KPI",
                new EnrichmentConfig.Input(dir.resolve("in").toString(), "PARQUET", List.of("day")),
                List.of(),
                new EnrichmentConfig.Output(out.toString(), "PARQUET", "snappy", List.of("day")),
                "SELECT 1");
        EnrichmentAuditWriter w = new EnrichmentAuditWriter(EnrichmentAuditWriter.auditDir(cfg), cfg.name());
        w.record(run("rc", "KPI", "SUCCESS", 1, 1, 10), List.of());

        List<Map<String, String>> runs = EnrichmentAuditReader.forConfig(cfg).runs();
        assertEquals(1, runs.size());
        assertEquals("rc", runs.get(0).get("run_id"));
    }
}
