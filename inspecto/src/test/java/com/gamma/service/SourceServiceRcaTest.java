package com.gamma.service;

import com.gamma.ops.rca.RcaTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link SourceService#loadRcaTemplates} scans {@code *_rca.toon}, registering the valid ones and
 *  warning + skipping any that fail to parse (mirrors the {@code loadAlerts} robustness contract). */
class SourceServiceRcaTest {

    @Test
    void loadsValidTemplatesAndSkipsBad(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("incident_rca.toon");
        Files.writeString(good, """
                rca:
                  name: standard-incident
                  sections[3]: Summary, "Root cause", Remediation
                """);
        Path bad = dir.resolve("broken_rca.toon");
        Files.writeString(bad, "rca:\n  name: broken\n");   // no sections → invalid, must be skipped

        List<RcaTemplate> loaded = SourceService.loadRcaTemplates(List.of(good, bad));
        assertEquals(1, loaded.size(), "the malformed template is skipped");
        assertEquals("standard-incident", loaded.get(0).name());
        assertEquals(3, loaded.get(0).sections().size());
    }
}
