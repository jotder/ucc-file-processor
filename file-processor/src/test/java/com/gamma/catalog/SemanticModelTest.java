package com.gamma.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SemanticModel} (P2): loading a {@code *_meta.toon} into the KPI catalog +
 * domain layer, including quoted colon-bearing node-id refs inside inline arrays.
 */
class SemanticModelTest {

    private static final String META = """
            name: events_semantics
            version: 1
            tables:
              events/CALL:
                description: "One row per voice call detail record"
                grain: "event_type, year, month, day"
            kpis:
              arpu:
                definition: "Average revenue per user per month"
                grain: "subscriber_id, month"
                inputs[2]: "events/CALL", "xform:events_daily"
                join_keys[1]: subscriber_id
              event_count:
                definition: "Count of events per type per day"
                grain: "event_type, day"
                inputs[1]: "events/CALL"
            reports:
              events_daily:
                description: "Daily KPI rollup per event type"
                uses[2]: "kpi:event_count", "xform:events_daily"
            domain:
              currency: USD
              timezone: UTC
              notes[1]: "revenue excludes tax"
            """;

    private static SemanticModel load(Path dir, String content) throws Exception {
        Path f = dir.resolve("events_meta.toon");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return SemanticModel.load(f.toString());
    }

    @Test
    void loadsTablesKpisReportsAndDomain(@TempDir Path dir) throws Exception {
        SemanticModel m = load(dir, META);

        assertEquals("events_semantics", m.name());

        // tables (keyed by the author's ref, which may contain '/')
        SemanticModel.TableMeta call = m.tables().get("events/CALL");
        assertNotNull(call);
        assertEquals("One row per voice call detail record", call.description());
        assertEquals("event_type, year, month, day", call.grain());

        // kpis — quoted colon-bearing refs survive intact inside inline arrays
        SemanticModel.KpiMeta arpu = m.kpis().get("arpu");
        assertNotNull(arpu);
        assertEquals("Average revenue per user per month", arpu.definition());
        assertEquals(List.of("events/CALL", "xform:events_daily"), arpu.inputs());
        assertEquals(List.of("subscriber_id"), arpu.joinKeys());
        assertTrue(m.kpis().containsKey("event_count"));

        // reports
        SemanticModel.ReportMeta rep = m.reports().get("events_daily");
        assertNotNull(rep);
        assertEquals(List.of("kpi:event_count", "xform:events_daily"), rep.uses());

        // domain
        assertEquals("USD", m.domain().currency());
        assertEquals("UTC", m.domain().timezone());
        assertEquals(List.of("revenue excludes tax"), m.domain().notes());
    }

    @Test
    void recordsAreImmutable(@TempDir Path dir) throws Exception {
        SemanticModel m = load(dir, META);
        assertThrows(UnsupportedOperationException.class,
                () -> m.kpis().get("arpu").inputs().add("x"));
        assertThrows(UnsupportedOperationException.class,
                () -> m.tables().put("x", null));
    }

    @Test
    void emptySectionsAreToleratedAndDomainDefaults(@TempDir Path dir) throws Exception {
        SemanticModel m = load(dir, "name: minimal\n");
        assertEquals("minimal", m.name());
        assertTrue(m.tables().isEmpty());
        assertTrue(m.kpis().isEmpty());
        assertTrue(m.reports().isEmpty());
        assertEquals(SemanticModel.DomainNotes.EMPTY, m.domain());
    }

    @Test
    void invalidKpiNameIsRejected(@TempDir Path dir) {
        String bad = """
                name: bad
                kpis:
                  bad-name:
                    definition: "x"
                """;
        assertThrows(IllegalArgumentException.class, () -> load(dir, bad),
                "a KPI name that is not a valid SQL identifier must fail the load");
    }
}
