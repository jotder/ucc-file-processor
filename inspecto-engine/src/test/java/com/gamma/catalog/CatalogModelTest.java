package com.gamma.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure metadata-graph model (P0): id formatting/parsing, description
 * provenance merging, and the null-safety / defensive-copy contracts of the record types.
 */
class CatalogModelTest {

    @Test
    void idSchemeFormatsEachKind() {
        assertEquals("stream:voucher", IdScheme.stream("voucher"));
        assertEquals("schema:voucher/main", IdScheme.schema("voucher", "main"));
        assertEquals("event:events/CALL", IdScheme.event("events", "CALL"));
        assertEquals("col:events/CALL/duration", IdScheme.column("events", "CALL", "duration"));
        assertEquals("xform:events_daily", IdScheme.xform("events_daily"));
        assertEquals("ref:events_daily/region_dim", IdScheme.reference("events_daily", "region_dim"));
        assertEquals("kpi:arpu", IdScheme.kpi("arpu"));
        assertEquals("report:events_daily", IdScheme.report("events_daily"));
    }

    @Test
    void idSchemeTokenRoundTripsForEveryKind() {
        for (NodeKind kind : NodeKind.values()) {
            String tok = IdScheme.token(kind);
            String id = tok + ":whatever/and/more";
            assertEquals(Optional.of(kind), IdScheme.kindOf(id),
                    "token '" + tok + "' must parse back to " + kind);
        }
    }

    @Test
    void kindOfRejectsUnknownOrMalformed() {
        assertEquals(Optional.empty(), IdScheme.kindOf(null));
        assertEquals(Optional.empty(), IdScheme.kindOf("noprefix"));
        assertEquals(Optional.empty(), IdScheme.kindOf(":leadingcolon"));
        assertEquals(Optional.empty(), IdScheme.kindOf("bogus:thing"));
    }

    @Test
    void descriptionMergeKeepsHigherAuthority() {
        Description manual = Description.manual("hand written");
        Description ai = new Description("ai guess", Provenance.AI);
        Description deduced = new Description("from name", Provenance.DEDUCED);

        // higher authority (lower ordinal) wins regardless of merge direction
        assertEquals(manual, manual.mergePreferring(ai));
        assertEquals(manual, ai.mergePreferring(manual));
        assertEquals(ai, deduced.mergePreferring(ai));
        assertEquals(ai, ai.mergePreferring(deduced));

        // EMPTY (NONE) loses to anything with prose
        assertEquals(deduced, Description.EMPTY.mergePreferring(deduced));
        assertEquals(manual, Description.EMPTY.mergePreferring(manual));
    }

    @Test
    void descriptionMergeKeepsIncumbentOnTieOrNull() {
        Description a = new Description("first", Provenance.AI);
        Description b = new Description("second", Provenance.AI);
        // same rank -> incumbent (this) is sticky
        assertEquals(a, a.mergePreferring(b));
        // null other -> unchanged
        assertEquals(a, a.mergePreferring(null));
    }

    @Test
    void descriptionNormalizesNullsAndManualBlank() {
        Description d = new Description(null, null);
        assertEquals("", d.text());
        assertEquals(Provenance.NONE, d.provenance());
        assertFalse(d.isPresent());
        assertTrue(new Description("x", Provenance.MANUAL).isPresent());

        // a blank manual description collapses to EMPTY (no false "authored" prose)
        assertEquals(Description.EMPTY, Description.manual("   "));
        assertEquals(Provenance.MANUAL, Description.manual("real").provenance());
    }

    @Test
    void nodeCopyHelpersPreserveOtherFields() {
        MetadataNode n = new MetadataNode("event:e/CALL", NodeKind.TABLE, "CALL",
                Description.EMPTY, Map.of("stage", 1));
        assertNull(n.overlay());

        MetadataNode withOverlay = n.withOverlay(OperationalOverlay.NO_DATA);
        assertSame(OperationalOverlay.NO_DATA, withOverlay.overlay());
        assertEquals(n.id(), withOverlay.id());
        assertEquals(n.attrs(), withOverlay.attrs());

        MetadataNode described = n.withDescription(Description.manual("a call detail record"));
        assertTrue(described.description().isPresent());
        assertEquals(NodeKind.TABLE, described.kind());
    }

    @Test
    void recordsAreDefensivelyCopied() {
        var nodes = new java.util.ArrayList<MetadataNode>();
        nodes.add(new MetadataNode("stream:a", NodeKind.STREAM, "a", Description.EMPTY, Map.of()));
        var edges = new java.util.ArrayList<MetadataEdge>();
        MetadataGraph g = new MetadataGraph(nodes, edges);

        nodes.clear();
        edges.clear();
        assertEquals(1, g.nodes().size(), "graph must hold its own copy of the node list");
        assertTrue(g.edges().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> g.nodes().add(null), "exposed list must be immutable");
    }

    @Test
    void overlayConstantsAreNullSafe() {
        assertEquals("UNKNOWN", OperationalOverlay.NONE.latestStatus());
        assertEquals("NO_DATA", OperationalOverlay.NO_DATA.latestStatus());
        OperationalOverlay o = new OperationalOverlay(null, null, 0, 0, 0, 0, null, null, false);
        assertEquals("UNKNOWN", o.latestStatus());
        assertEquals("", o.lastError());
        assertTrue(o.lineageRefs().isEmpty());
    }
}
