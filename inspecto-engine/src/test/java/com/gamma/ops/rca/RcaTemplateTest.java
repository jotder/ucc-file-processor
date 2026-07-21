package com.gamma.ops.rca;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** RCA template parsing — {@code fromMap} validation + a {@code *_rca.toon} round-trip (reuses ConfigCodec). */
class RcaTemplateTest {

    @Test
    void fromMapParsesAndValidates() {
        RcaTemplate t = RcaTemplate.fromMap(Map.of("name", "incident",
                "sections", List.of("Summary", "Timeline", "Root cause", "Remediation")));
        assertEquals("incident", t.name());
        assertEquals(4, t.sections().size());
        assertEquals("Summary", t.sections().get(0));
        assertThrows(IllegalArgumentException.class, () -> RcaTemplate.fromMap(Map.of("name", "x")),
                "needs at least one section");
        assertThrows(IllegalArgumentException.class, () -> RcaTemplate.fromMap(Map.of("sections", List.of("a"))),
                "name is required");
    }

    @Test
    void loadFromToonFile(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("incident_rca.toon");
        Files.writeString(p, """
                rca:
                  name: standard-incident
                  sections[3]: Summary, "Root cause", Remediation
                """);
        RcaTemplate t = RcaTemplate.load(p);
        assertEquals("standard-incident", t.name());
        assertEquals(3, t.sections().size());
        assertEquals("Root cause", t.sections().get(1));
        assertEquals("Remediation", t.sections().get(2));
    }
}
