package com.gamma.job;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** BI-4 scheduled export delivery: the dataset scope renders a CSV artifact into out_dir. */
class ReportJobDeliveryTest {

    @AfterEach
    void clearRoot() {
        System.clearProperty("assist.write.root");
    }

    private static void seedSales(Path writeRoot) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('EU',30.0),('US',5.0)) AS t(region,amount)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view"));
    }

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("weekly_sales", JobType.REPORT, null, null, true, false, params);
    }

    @Test
    void datasetScopeDeliversAggregatedCsv(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());

        JobResult r = new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds",
                "measures", "sum(amount),count", "group_by", "region",
                "out_dir", outDir.toString())), null).run();

        assertEquals("SUCCESS", r.status(), r.message());
        assertTrue(r.message().contains("delivered to"), r.message());
        Path artifact;
        try (Stream<Path> files = Files.list(outDir)) { artifact = files.findFirst().orElseThrow(); }
        assertTrue(artifact.getFileName().toString().matches("weekly_sales_\\d{8}_\\d{6}\\.csv"),
                artifact.toString());
        String csv = Files.readString(artifact);
        assertTrue(csv.startsWith("region,sum_amount,count"), csv);
        assertTrue(csv.contains("EU,40.0,2") && csv.contains("US,5.0,1"), csv);
    }

    @Test
    void rawExportWithoutMeasuresDeliversAllRows(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());

        JobResult r = new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds", "out_dir", outDir.toString())), null).run();

        assertEquals("SUCCESS", r.status(), r.message());
        Path artifact;
        try (Stream<Path> files = Files.list(outDir)) { artifact = files.findFirst().orElseThrow(); }
        assertEquals(4, Files.readAllLines(artifact).size(), "header + all 3 raw rows");
    }

    @Test
    void datasetScopeWithoutWriteRootFails(@TempDir Path outDir) {
        assertThrows(IllegalStateException.class, () -> new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds", "out_dir", outDir.toString())), null).run());
    }
}
