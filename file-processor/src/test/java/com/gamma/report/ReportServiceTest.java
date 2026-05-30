package com.gamma.report;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReportService} — it must roll the raw {@code StatusStore} audit up
 * into a live status snapshot and a historical batch-audit report after a real run.
 */
class ReportServiceTest {

    private static Path seed(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        return toon;
    }

    @Test
    void statusAndBatchReportsReflectARun(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        try (SourceService svc = new SourceService(List.of(toon), 3600, 1)) {
            svc.runAllOnce();

            ReportService reports = svc.reports();

            ReportService.StatusReport status = reports.statusReport();
            assertEquals(1, status.pipelineCount());
            assertEquals(0, status.pausedCount());
            assertTrue(status.totalCommittedBatches() >= 1, "a batch committed");
            ReportService.PipelineStatus ps = status.pipelines().get(0);
            assertEquals("test_etl", ps.pipeline());
            assertTrue(ps.committedBatches() >= 1);
            assertEquals("SUCCESS", ps.lastBatchStatus(), "last batch outcome surfaced");

            ReportService.BatchAuditReport br = reports.batchReport("test_etl");
            assertTrue(br.totalBatches() >= 1);
            assertEquals(br.totalBatches(), br.success(), "all batches succeeded");
            assertEquals(0, br.failed());
            assertEquals(0.0, br.errorRate());
            assertTrue(br.totalOutputRows() >= 3, "three input rows materialised");

            ReportService.ServiceReport sr = reports.serviceReport();
            assertEquals(1, sr.pipelines().size());
            assertTrue(sr.totalBatches() >= 1);
            assertEquals(sr.totalBatches(), sr.success());
            assertEquals(0.0, sr.errorRate());
        }
    }

    @Test
    void batchReportForUnknownPipelineThrows(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        try (SourceService svc = new SourceService(List.of(toon), 3600, 1)) {
            assertThrows(IllegalArgumentException.class, () -> svc.reports().batchReport("ghost"));
        }
    }

    @Test
    void freshServiceReportsZeroedRollup(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        try (SourceService svc = new SourceService(List.of(toon), 3600, 1)) {
            // before any run: pipeline registered, nothing committed
            ReportService.StatusReport status = svc.reports().statusReport();
            assertEquals(1, status.pipelineCount());
            assertEquals(0, status.totalCommittedBatches());
            ReportService.BatchAuditReport br = svc.reports().batchReport("test_etl");
            assertEquals(0, br.totalBatches());
            assertEquals(0.0, br.errorRate());
        }
    }
}
