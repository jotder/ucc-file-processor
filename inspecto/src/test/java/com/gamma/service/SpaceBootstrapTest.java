package com.gamma.service;

import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Builds one space's runtime from its own {@code config/} directory (the per-space analogue of ServiceBootstrap). */
class SpaceBootstrapTest {

    @Test
    void buildsAnEmptySpaceWithManifestAndPerSpaceLedger(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("space-a");
        Files.createDirectories(base.resolve("config"));     // empty config tree — a freshly created space
        Files.writeString(base.resolve("space.toon"),
                "display_name: \"Space A\"\ndescription: \"a test space\"\ncreated_at: \"2026-06-22\"\n");

        try (SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base))) {
            assertEquals("space-a", ctx.id().value(), "id is the directory name");
            assertEquals("Space A", ctx.manifest().displayName(), "display name from space.toon");
            assertEquals("a test space", ctx.manifest().description());
            assertNotNull(ctx.service(), "an empty space still builds a service (no System.exit)");
            assertTrue(ctx.service().pipelines().isEmpty(), "no pipelines in an empty config dir");

            // The space's acquisition ledger is registered under its id — visible only under that space's MDC.
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            try {
                AcquisitionLedger scoped = AcquisitionLedgers.shared();
                assertNotNull(scoped, "space-a has its own registered ledger");
            } finally {
                MDC.remove(EventLog.SPACE_MDC_KEY);
            }
        } finally {
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            try { AcquisitionLedgers.use(null); }   // drop the test space's ledger entry
            finally { MDC.remove(EventLog.SPACE_MDC_KEY); }
        }
    }

    @Test
    void manifestDefaultsToIdWhenNoSpaceToon(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("space-b");
        Files.createDirectories(base.resolve("config"));     // no space.toon

        try (SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base))) {
            assertEquals("space-b", ctx.id().value());
            assertEquals("space-b", ctx.manifest().displayName(), "display name defaults to the id");
            assertEquals("", ctx.manifest().description());
        } finally {
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            try { AcquisitionLedgers.use(null); }
            finally { MDC.remove(EventLog.SPACE_MDC_KEY); }
        }
    }
}
