package com.gamma.job;

import com.gamma.pipeline.ComponentStore;
import com.gamma.service.BatchEventBus;
import com.gamma.service.Scheduler;
import com.gamma.signal.Severity;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code recon.run} Job Type: runs a saved {@code reconciliation} over its Datasets and emits a
 * {@code recon.run.completed} Signal with the Break counts. Mirrors {@code ControlApiReconTest}'s example
 * (orders_a vs orders_b → 1 missing-left, 1 missing-right, 1 value-break) but drives the Job directly with
 * a capturing {@link JobContext}, and confirms the type is registered as a built-in.
 */
class ReconRunJobTest {

    @AfterEach
    void clearWriteRoot() {
        System.clearProperty("assist.write.root");
    }

    @Test
    void runsSavedReconciliationAndEmitsBreakCounts(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        Path dataDir = dir.resolve("data");
        // the design doc's example: EU/voice matches, EU/data value-break (118 vs 114), MEA only in a, APAC only in b
        seedStore(dataDir, "orders_a", "VALUES ('EU','voice',100.0),('EU','data',118.0),('MEA','voice',10.0)");
        seedStore(dataDir, "orders_b", "VALUES ('EU','voice',100.0),('EU','data',114.0),('APAC','sms',7.0)");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.write("dataset", "a_ds", Map.of("physicalRef", "orders_a"));
        store.write("dataset", "b_ds", Map.of("physicalRef", "orders_b"));
        store.write("reconciliation", "orders_recon", Map.of(
                "datasets", List.of("a_ds", "b_ds"),
                "keyColumns", List.of("region", "product"),
                "compareColumns", List.of(Map.of("column", "amount", "toleranceType", "percent", "tolerance", 0.5))));
        System.setProperty("assist.write.root", writeRoot.toString());

        JobConfig cfg = new JobConfig("nightly_recon", "recon.run", null, null, true, false,
                Map.of("reconciliation", "orders_recon"), null, null);
        CapturingContext ctx = new CapturingContext(Map.of("reconciliation", "orders_recon"));
        JobResult result = new ReconRunJob(cfg, dataDir.toString(), () -> null).run(ctx);

        assertEquals("SUCCESS", result.status(), result.message());
        assertTrue(result.message().contains("3 break(s)"), result.message());

        assertEquals("recon.run.completed", ctx.type.get());
        assertEquals(Severity.WARN, ctx.severity.get(), "breaks present ⇒ WARN");
        Map<String, Object> p = ctx.payload.get();
        assertEquals("orders_recon", p.get("reconciliation"));
        assertEquals(1L, ((Number) p.get("missingLeft")).longValue(), "APAC only in b");
        assertEquals(1L, ((Number) p.get("missingRight")).longValue(), "MEA only in a");
        assertEquals(1L, ((Number) p.get("valueBreak")).longValue(), "EU/data 118 vs 114 outside 0.5%");
        assertEquals(3L, ((Number) p.get("breaks")).longValue());
    }

    @Test
    void breachOpensAnIncidentDedupedPerReconciliation(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        Path dataDir = dir.resolve("data");
        seedStore(dataDir, "orders_a", "VALUES ('EU','voice',100.0),('EU','data',118.0),('MEA','voice',10.0)");
        seedStore(dataDir, "orders_b", "VALUES ('EU','voice',100.0),('EU','data',114.0),('APAC','sms',7.0)");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.write("dataset", "a_ds", Map.of("physicalRef", "orders_a"));
        store.write("dataset", "b_ds", Map.of("physicalRef", "orders_b"));
        store.write("reconciliation", "orders_recon", Map.of(
                "datasets", List.of("a_ds", "b_ds"),
                "keyColumns", List.of("region", "product"),
                "compareColumns", List.of(Map.of("column", "amount", "toleranceType", "percent", "tolerance", 0.5))));
        System.setProperty("assist.write.root", writeRoot.toString());

        com.gamma.ops.ObjectService objects = new com.gamma.ops.ObjectService(new com.gamma.ops.InMemoryObjectStore());
        JobConfig cfg = new JobConfig("nightly_recon", "recon.run", null, null, true, false,
                Map.of("reconciliation", "orders_recon"), null, null);
        ReconRunJob job = new ReconRunJob(cfg, dataDir.toString(), () -> objects);

        job.run(new CapturingContext(Map.of("reconciliation", "orders_recon")));
        assertEquals(1, incidentCount(objects), "3 breaks ⇒ one Incident opened");

        // a second run while the first Incident is still open must not clone it
        job.run(new CapturingContext(Map.of("reconciliation", "orders_recon")));
        assertEquals(1, incidentCount(objects), "deduped to one open Incident per reconciliation");
    }

    private static int incidentCount(com.gamma.ops.ObjectService objects) {
        return objects.query(com.gamma.ops.ObjectQuery.builder()
                .objectType(com.gamma.ops.ObjectType.INCIDENT).build()).size();
    }

    @Test
    void unknownReconciliationFailsClosed(@TempDir Path dir) throws Exception {
        System.setProperty("assist.write.root", dir.resolve("cfg").toString());
        JobConfig cfg = new JobConfig("r", "recon.run", null, null, true, false,
                Map.of("reconciliation", "ghost"), null, null);
        ReconRunJob job = new ReconRunJob(cfg, dir.resolve("data").toString(), () -> null);
        assertThrows(IllegalArgumentException.class, () -> job.run(new CapturingContext(Map.of())));
    }

    @Test
    void reconRunIsRegisteredAsABuiltInType() throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new BatchEventBus(), s, null,
                     "audit", null, null, "data")) {
            assertTrue(js.jobType("recon.run").isPresent(), "recon.run registered as a built-in Job Type");
            assertEquals("Reconciliation Run", js.jobType("recon.run").get().title());
        }
    }

    private static void seedStore(Path dataDir, String name, String values) throws Exception {
        Path partition = dataDir.resolve(name).resolve("dt=2026");
        Files.createDirectories(partition);
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("recon_job_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (" + values + ") t(region, product, amount)) TO '"
                    + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** A {@link JobContext} that captures the one Signal the Job emits. */
    private static final class CapturingContext implements JobContext {
        final AtomicReference<String> type = new AtomicReference<>();
        final AtomicReference<Severity> severity = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        private final Map<String, String> params;

        CapturingContext(Map<String, String> params) { this.params = params; }

        @Override public String runId() { return "test-run"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return params; }
        @Override public Map<String, String> params() { return params; }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String message, Object... kv) {}
                @Override public void warn(String message, Object... kv) {}
                @Override public void error(String message, Throwable t, Object... kv) {}
            };
        }
        @Override public SignalEmitter signals() {
            return (t, sev, p) -> { type.set(t); severity.set(sev); payload.set(p); };
        }
        @Override public ArtifactRecorder artifacts() { return null; }
    }
}
