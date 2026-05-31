package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the O(1) config index (P3): lookups address pipelines by their <b>in-file
 * identity</b> (not their filename), duplicate identities are reconciled rather than silently
 * shadowing, unloadable paths are skipped, and the rebuild callback fires (the catalog-invalidation
 * hook). {@code PipelineConfigBatchTest.writePipeline} emits {@code mini_pipeline.toon} declaring
 * {@code name: MINI_ETL} → id {@code mini_etl}, so the filename prefix ("mini") deliberately differs
 * from the identity ("mini_etl") to prove the keying.
 */
class ConfigRegistryTest {

    @Test
    void indexesByInFileIdentityNotFilename(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(p));

        assertEquals(1, reg.size());
        assertTrue(reg.get("mini_etl").isPresent(), "lookup by in-file identity");
        assertTrue(reg.get("mini").isEmpty(), "the filename prefix is NOT the key");
        assertEquals(p, reg.getPath("mini_etl").orElseThrow());
        assertEquals("mini_etl", reg.idForPath(p).orElseThrow());
        assertEquals(1, reg.configs().size());
        assertEquals("mini_etl", reg.all().get(0).id());
    }

    @Test
    void duplicateInFileIdentityKeepsLatter(@TempDir Path dir) throws Exception {
        Path da = Files.createDirectories(dir.resolve("a"));
        Path db = Files.createDirectories(dir.resolve("b"));
        Path a = PipelineConfigBatchTest.writePipeline(da, "");
        Path b = PipelineConfigBatchTest.writePipeline(db, "");

        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(a, b)); // both declare name MINI_ETL → same id
        assertEquals(1, reg.size(), "the same identity collapses to one entry");
        assertEquals(b, reg.getPath("mini_etl").orElseThrow(), "the later path wins");
    }

    @Test
    void unloadablePathSkippedButCallbackFires(@TempDir Path dir) throws Exception {
        Path good = PipelineConfigBatchTest.writePipeline(dir, "");
        Path missing = dir.resolve("nope_pipeline.toon"); // never written
        AtomicInteger rebuilds = new AtomicInteger();
        ConfigRegistry reg = new ConfigRegistry(rebuilds::incrementAndGet);

        reg.rebuild(List.of(good, missing));
        assertEquals(1, reg.size(), "the unloadable path is warned and skipped");
        assertEquals(1, rebuilds.get(), "the rebuild callback fires once");

        reg.rebuild(List.of(good));
        assertEquals(2, rebuilds.get(), "callback fires on every rebuild");
    }

    @Test
    void emptyRegistryReadsAreSafe() {
        ConfigRegistry reg = new ConfigRegistry();
        assertEquals(0, reg.size());
        assertTrue(reg.get("x").isEmpty());
        assertTrue(reg.getPath("x").isEmpty());
        assertTrue(reg.idForPath(Path.of("whatever")).isEmpty());
        assertTrue(reg.configs().isEmpty());
        assertTrue(reg.all().isEmpty());
    }
}
