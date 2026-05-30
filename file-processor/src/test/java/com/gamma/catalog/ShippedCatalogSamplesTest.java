package com.gamma.catalog;

import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the shipped sample catalog artifacts under {@code config/events/} are valid and
 * round-trip (P7): the described {@code call_schema.toon} projects domain-described columns, and
 * {@code events_meta.toon} loads into a KPI catalog with quoted colon-bearing refs intact. Runs in
 * CI, so a future edit that breaks the shipped samples fails the build.
 */
class ShippedCatalogSamplesTest {

    private static final Path EVENTS = Path.of("config", "events");

    @Test
    void describedSchemaProjectsDomainColumns() throws Exception {
        Path schema = EVENTS.resolve("call_schema.toon");
        assertTrue(Files.exists(schema), "shipped sample present: " + schema.toAbsolutePath());

        List<SchemaProjection.Column> cols = SchemaProjection.columns(ToonHelper.load(schema.toString()));
        assertEquals(7, cols.size());

        SchemaProjection.Column callId = cols.get(0);
        assertEquals("CALL_ID", callId.name());
        assertEquals(Provenance.MANUAL, callId.description().provenance());
        assertFalse(callId.description().text().isBlank());

        assertEquals("PII", col(cols, "MSISDN").classification());
        assertEquals("seconds", col(cols, "DURATION_SEC").unit());
        // empty optional columns project as blank, not garbage
        assertEquals("", col(cols, "CALL_ID").unit());
    }

    @Test
    void shippedMetaLoadsKpiCatalogAndDomain() throws Exception {
        Path meta = EVENTS.resolve("events_meta.toon");
        assertTrue(Files.exists(meta), "shipped sample present: " + meta.toAbsolutePath());

        SemanticModel m = SemanticModel.load(meta.toString());
        assertEquals("events_semantics", m.name());
        assertTrue(m.kpis().containsKey("event_count"));

        SemanticModel.KpiMeta arpu = m.kpis().get("arpu");
        assertNotNull(arpu);
        // quoted colon-/slash-bearing refs survive intact
        assertEquals(List.of("events/CALL", "EVENTS_DAILY_KPI"), arpu.inputs());

        assertEquals("USD", m.domain().currency());
        assertEquals(2, m.domain().notes().size());
        assertTrue(m.tables().containsKey("events/CALL"));
    }

    private static SchemaProjection.Column col(List<SchemaProjection.Column> cols, String name) {
        return cols.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }
}
