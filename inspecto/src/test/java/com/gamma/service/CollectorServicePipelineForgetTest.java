package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the S4/E1 map-leak fix: within a single long-lived {@link CollectorService}
 * (i.e. per space), deleting a pipeline must prune its per-pipeline bookkeeping — the
 * {@link PipelineScheduler} cadence/coalescer maps and the {@code paused} set — so a space with high
 * pipeline churn (register/rename/delete without a restart) cannot accumulate orphan entries.
 *
 * <p>Space <em>teardown</em> was already clean (every space-id-keyed static map is unwound by
 * {@code SpaceManager.delete} → {@code CollectorService.close}); this test covers the narrower
 * pipeline-delete-within-a-space path that {@code unregisterPipeline} previously missed. The scheduler
 * maps are private, so — like {@link CollectorServiceIngestLockTest} — the test reads them reflectively.
 */
class CollectorServicePipelineForgetTest {

    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n";

    private static Path source(Path root) throws Exception {
        Path toon = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), CSV);
        return toon;
    }

    @Test
    void unregisterPrunesTheSchedulerCadenceEntry(@TempDir Path dir) throws Exception {
        Path a = source(dir);
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            String id = svc.pipelines().get(0).name();
            svc.runAllOnce();                                    // a cycle stamps lastRunAtMs[id]
            Map<String, ?> cadence = cadenceMap(svc);
            assertTrue(cadence.containsKey(id), "a run should stamp the pipeline's cadence baseline");

            assertTrue(svc.unregisterPipeline(a.toAbsolutePath().normalize()));
            assertFalse(cadence.containsKey(id),
                    "unregisterPipeline must prune the cadence entry — otherwise it leaks under churn");
        }
    }

    @Test
    void unregisterDropsAPausedThenDeletedPipeline(@TempDir Path dir) throws Exception {
        Path a = source(dir);
        try (CollectorService svc = new CollectorService(List.of(a), 3600, 1)) {
            String id = svc.pipelines().get(0).name();
            assertTrue(svc.pause(id));
            Set<String> paused = pausedSet(svc);
            assertTrue(paused.contains(id), "precondition: the pipeline is paused");

            assertTrue(svc.unregisterPipeline(a.toAbsolutePath().normalize()));
            assertFalse(paused.contains(id),
                    "unregisterPipeline must drop the paused entry of a deleted pipeline");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cadenceMap(CollectorService svc) throws Exception {
        Field schedField = CollectorService.class.getDeclaredField("pipelineScheduler");
        schedField.setAccessible(true);
        Object scheduler = schedField.get(svc);
        Field mapField = PipelineScheduler.class.getDeclaredField("lastRunAtMs");
        mapField.setAccessible(true);
        return (Map<String, ?>) mapField.get(scheduler);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> pausedSet(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("paused");
        f.setAccessible(true);
        return (Set<String>) f.get(svc);
    }
}
