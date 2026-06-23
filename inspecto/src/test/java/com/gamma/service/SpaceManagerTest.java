package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The multi-space container: single-service wrapping, boot discovery, concurrent isolation, orderly shutdown. */
class SpaceManagerTest {

    @Test
    void singleWrapsOneServiceAsTheDefaultSpace() {
        SourceService svc = new SourceService(List.of(), 60, 1);
        try (SpaceManager mgr = SpaceManager.single(svc)) {   // mgr.close() drains svc — don't double-close it
            assertEquals(1, mgr.size());
            assertSame(svc, mgr.current().service(), "current() resolves the wrapped service");
            assertEquals("default", mgr.current().id().value());
            assertTrue(mgr.space(SpaceId.of("default")).isPresent());
        }
    }

    @Test
    void discoverBootsEachSpaceDirAndSkipsNonSpaces(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("space-a").resolve("config"));
        Files.createDirectories(root.resolve("space-b").resolve("config"));
        Files.createDirectories(root.resolve("not-a-space"));     // no config/ subtree → not a space
        Files.writeString(root.resolve("loose-file.txt"), "ignored");

        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertEquals(2, mgr.size(), "only dirs with a config/ subtree are booted");
            SpaceContext a = mgr.space(SpaceId.of("space-a")).orElseThrow();
            SpaceContext b = mgr.space(SpaceId.of("space-b")).orElseThrow();
            assertNotSame(a, b);
            assertNotSame(a.service(), b.service(), "each space gets its own isolated service");
            assertNotEquals(a.root().base(), b.root().base(), "each space gets its own root");
            assertTrue(mgr.space(SpaceId.of("not-a-space")).isEmpty(), "a dir without config/ is skipped");
        }
    }

    @Test
    void discoverOnAMissingRootYieldsNoSpaces(@TempDir Path root) throws Exception {
        try (SpaceManager mgr = SpaceManager.discover(root.resolve("does-not-exist"))) {
            assertEquals(0, mgr.size());
        }
    }

    @Test
    void createMintsBootsAndRegistersANewSpace(@TempDir Path root) throws Exception {
        try (SpaceManager mgr = SpaceManager.discover(root)) {   // empty container → 0 spaces, but root is remembered
            assertTrue(mgr.supportsCrud());
            SpaceContext ctx = mgr.create(SpaceId.of("acme"), "ACME Corp", "the acme space");
            assertEquals(1, mgr.size());
            assertSame(ctx, mgr.space(SpaceId.of("acme")).orElseThrow());
            assertEquals("ACME Corp", ctx.manifest().displayName(), "the written manifest is read back into the context");
            Path base = root.resolve("acme");
            assertTrue(Files.isDirectory(base.resolve("config")), "config dir created");
            assertTrue(Files.isDirectory(base.resolve("duckdb")), "duckdb dir created");
            assertTrue(Files.exists(base.resolve("space.toon")), "manifest written");
        }
    }

    @Test
    void createRejectsADuplicate(@TempDir Path root) throws Exception {
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            mgr.create(SpaceId.of("acme"), null, null);   // null display name → defaults to the id
            assertThrows(IllegalStateException.class, () -> mgr.create(SpaceId.of("acme"), null, null));
            assertEquals(1, mgr.size());
        }
    }

    @Test
    void deleteWithoutPurgeDeregistersButKeepsFiles(@TempDir Path root) throws Exception {
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            mgr.create(SpaceId.of("acme"), null, null);
            assertTrue(mgr.delete(SpaceId.of("acme"), false));
            assertEquals(0, mgr.size());
            assertTrue(mgr.space(SpaceId.of("acme")).isEmpty(), "deregistered → later requests 404");
            assertTrue(Files.isDirectory(root.resolve("acme")), "files left on disk when not purging");
            assertFalse(mgr.delete(SpaceId.of("acme"), false), "deleting an absent space is a no-op");
        }
    }

    @Test
    void deleteWithPurgeRemovesTheDirectory(@TempDir Path root) throws Exception {
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            mgr.create(SpaceId.of("acme"), null, null);
            assertTrue(mgr.delete(SpaceId.of("acme"), true));
            assertEquals(0, mgr.size());
            assertFalse(Files.exists(root.resolve("acme")), "purge removed the space directory tree");
        }
    }

    @Test
    void crudIsUnsupportedInSingleTenantMode() {
        SourceService svc = new SourceService(List.of(), 60, 1);
        try (SpaceManager mgr = SpaceManager.single(svc)) {   // mgr.close() drains svc — don't double-close it
            assertFalse(mgr.supportsCrud());
            assertThrows(IllegalStateException.class, () -> mgr.create(SpaceId.of("acme"), null, null));
            assertThrows(IllegalStateException.class, () -> mgr.delete(SpaceId.of("default"), false));
        }
    }
}
