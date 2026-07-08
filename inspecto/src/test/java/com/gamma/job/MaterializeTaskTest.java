package com.gamma.job;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAT-4 Matrix materialization, end-to-end on real DuckDB: the {@code materialize} task compiles a
 * measure spec over a view-backed source Dataset, lands one Parquet snapshot atomically, registers the
 * target {@code dataset} component, and a refresh replaces the snapshot (never accumulates). The
 * materialized Matrix is then queried back through the normal Dataset read path.
 */
class MaterializeTaskTest {

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("mx", JobType.MAINTENANCE, null, null, true, false, params);
    }

    /** Seed a VALUES-backed view + dataset 'sales_ds' under {@code writeRoot}. */
    private static void seedSource(Path writeRoot) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('EU',30.0),('US',5.0)) AS t(region,amount)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view"));
    }

    private static List<Path> parquetFiles(Path dir) throws Exception {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.parquet")) {
            for (Path p : ds) out.add(p);
        }
        return out;
    }

    @Test
    void materializesRefreshesAndReadsBackAsADataset(@TempDir Path writeRoot, @TempDir Path dataDir) throws Exception {
        seedSource(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            JobConfig cfg = job(Map.of("task", "materialize", "dataset", "sales_ds",
                    "target", "sales_by_region", "measures", "sum(amount),count", "group_by", "region"));

            JobResult first = new MaintenanceJob(cfg, dataDir.toString()).run();
            assertTrue(first.message().contains("2 row(s)"), first.message());

            Path outDir = dataDir.resolve("sales_by_region");
            assertEquals(1, parquetFiles(outDir).size(), "exactly one visible snapshot");

            // the target dataset component was registered, physicalRef-backed
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            Map<String, Object> content = store.get("dataset", "sales_by_region")
                    .map(ComponentRegistry.Component::content).orElseThrow();
            assertEquals("sales_by_region", content.get("physicalRef"));

            // the Matrix reads back through the NORMAL dataset path with the aggregated rows
            String relation = DatasetRelation.relationSql(content, dataDir, new ViewStore(writeRoot.resolve("views")));
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    "sales_by_region", relation, "SELECT * FROM \"sales_by_region\"",
                    100, 0, List.of(), List.of(new QueryExecutor.Sort("sum_amount", true))));
            assertEquals(2, r.rowCount());
            assertEquals("EU", r.rows().get(0).get("region"));
            assertEquals(40.0, ((Number) r.rows().get(0).get("sum_amount")).doubleValue(), 1e-9);
            assertEquals(2L, ((Number) r.rows().get(0).get("count")).longValue());

            // refresh: a second run REPLACES the snapshot (atomic swap), never accumulates
            JobResult second = new MaintenanceJob(cfg, dataDir.toString()).run();
            assertTrue(second.message().contains("refreshed"), second.message());
            assertEquals(1, parquetFiles(outDir).size(), "still exactly one visible snapshot after refresh");
            try (DirectoryStream<Path> junk = Files.newDirectoryStream(outDir, "*.{tmp,stale}")) {
                assertFalse(junk.iterator().hasNext(), "no invisible leftovers after a clean swap");
            }
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    @Test
    void rawSnapshotWithoutSpecCopiesAllRows(@TempDir Path writeRoot, @TempDir Path dataDir) throws Exception {
        seedSource(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            JobResult r = new MaintenanceJob(job(Map.of("task", "materialize", "dataset", "sales_ds",
                    "target", "sales_snapshot")), dataDir.toString()).run();
            assertTrue(r.message().contains("3 row(s)"), r.message());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    @Test
    void failClosedGates(@TempDir Path writeRoot, @TempDir Path dataDir) throws Exception {
        seedSource(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            assertThrows(IllegalArgumentException.class, () -> new MaintenanceJob(
                    job(Map.of("task", "materialize", "dataset", "sales_ds", "target", "sales_ds")),
                    dataDir.toString()).run(), "target == source rejected");
            assertThrows(IllegalArgumentException.class, () -> new MaintenanceJob(
                    job(Map.of("task", "materialize", "dataset", "sales_ds", "target", "../evil")),
                    dataDir.toString()).run(), "path-escaping target rejected");
            assertThrows(IllegalArgumentException.class, () -> new MaintenanceJob(
                    job(Map.of("task", "materialize", "dataset", "ghost", "target", "t")),
                    dataDir.toString()).run(), "unknown source dataset rejected");
            assertThrows(IllegalStateException.class, () -> new MaintenanceJob(
                    job(Map.of("task", "materialize", "dataset", "sales_ds", "target", "t")),
                    null).run(), "no data root rejected");
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    @Test
    void calculatedColumnsFeedTheSpec(@TempDir Path writeRoot, @TempDir Path dataDir) throws Exception {
        // DAT-5 × DAT-4: a calculated column on the source dataset is a real column to materialize over.
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('EU',30.0),('US',5.0)) AS t(region,amount)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "sales_ds", Map.of(
                "view", "sales_view",
                "calculated", List.of(Map.of("name", "amount_taxed", "expr", "round(amount * 1.2, 2)"))));
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            JobResult r = new MaintenanceJob(job(Map.of("task", "materialize", "dataset", "sales_ds",
                    "target", "taxed_by_region", "measures", "sum(amount_taxed)", "group_by", "region")),
                    dataDir.toString()).run();
            assertTrue(r.message().contains("2 row(s)"), r.message());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }
}
