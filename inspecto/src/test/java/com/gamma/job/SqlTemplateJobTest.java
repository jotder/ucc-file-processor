package com.gamma.job;

import com.gamma.service.BatchEventBus;
import com.gamma.service.Scheduler;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3b end-to-end: the {@code sql.template} Job Type runs an authored template (its {@code $event_date}
 * token resolved by the framework, §7.2) over a source Dataset registered as a DuckDB view, materializes
 * the result as a snapshot Parquet Dataset, and records a queryable Run Artifact (§10/§15.1).
 */
class SqlTemplateJobTest {

    private static JobRun await(Supplier<JobRun> s) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        JobRun r;
        while ((r = s.get()) == null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNotNull(r, "expected a job run within 10s");
        return r;
    }

    /** COPY a tiny transactions store to {@code <dataDir>/transactions/data.parquet}. */
    private static void seedTransactions(Path dataDir) throws Exception {
        DuckDbUtil.loadDriver();
        Path store = dataDir.resolve("transactions");
        Files.createDirectories(store);
        String out = store.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t AS SELECT * FROM (VALUES "
                    + "(1, DATE '2026-07-07', 100.0),"
                    + "(1, DATE '2026-07-07', 50.0),"
                    + "(2, DATE '2026-07-07', 25.0),"
                    + "(1, DATE '2026-07-06', 999.0)"
                    + ") AS v(account_id, event_date, amount)");
            st.execute("COPY t TO '" + out + "' (FORMAT PARQUET)");
        }
    }

    @Test
    void runsTemplateWritesDatasetAndRecordsArtifact(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        seedTransactions(dataDir);

        String sql = "SELECT account_id, count(*) AS tx_count, sum(amount) AS total "
                + "FROM transactions WHERE event_date = $event_date GROUP BY account_id ORDER BY account_id";
        JobConfig job = new JobConfig("rollup", "sql.template", null, null, true, false,
                Map.of("sql", sql, "sources", "transactions", "sink_dataset", "txn_rollup",
                        "event_date", "2026-07-07"),
                null, null);

        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            assertTrue(js.triggerRun("rollup", null).isPresent());
            JobRun run = await(() -> js.lastRunOf("rollup").orElse(null));
            assertEquals("SUCCESS", run.status(), "run failed: " + run.message());

            // the sink Dataset snapshot is revealed under its stable name
            Path sinkDir = dataDir.resolve("txn_rollup");
            try (DirectoryStream<Path> files = Files.newDirectoryStream(sinkDir, "*.parquet")) {
                assertTrue(files.iterator().hasNext(), "a snapshot parquet was revealed");
            }

            // the Run Artifact is recorded and queryable (feeds $upstream)
            List<RunArtifact> arts = js.latestArtifacts("rollup");
            assertEquals(1, arts.size(), "one dataset artifact");
            RunArtifact a = arts.get(0);
            assertEquals("dataset", a.kind());
            assertEquals("txn_rollup", a.ref());
            assertEquals(2L, a.rows(), "only the 2026-07-07 partition's two accounts (the WHERE $event_date filter applied)");
            assertNotNull(a.resultSet(), "the output shape is captured");
            assertEquals(3, a.resultSet().columns().size(), "account_id, tx_count, total");
        }
    }

    @Test
    void missingRequiredSqlParameterRejectsBeforeRunning(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        seedTransactions(dataDir);
        // $event_date is scanned as required but supplied nowhere ⇒ REJECTED before any SQL runs (§7.2).
        JobConfig job = new JobConfig("rollup", "sql.template", null, null, true, false,
                Map.of("sql", "SELECT * FROM transactions WHERE event_date = $event_date",
                        "sources", "transactions", "sink_dataset", "txn_rollup"),
                null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            assertTrue(js.trigger("rollup"));
            JobRun run = await(() -> js.lastRunOf("rollup").orElse(null));
            assertEquals("REJECTED", run.status());
            assertTrue(run.message().contains("event_date"), "names the missing SQL parameter: " + run.message());
        }
    }
}
