package com.gamma.ops;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The Phase-2 value types: {@link OperationalObject} (builder/withStatus/toMap), {@link ObjectType}
 *  parsing, and {@link ObjectQuery} matching + paging clamps. */
class ObjectCoreTest {

    @Test
    void builderDefaultsIdTimestampsAndAttributes() {
        OperationalObject o = OperationalObject.builder(ObjectType.ALERT)
                .title("t").description("d").severity("WARNING").status("OPEN")
                .correlationId("pipe").attr("rule", "r").attr("nullValue", null).build();
        assertTrue(o.id().startsWith("ALERT-"), "id auto-prefixed by type");
        assertEquals(ObjectType.ALERT, o.objectType());
        assertEquals("OPEN", o.status());
        assertEquals("r", o.attributes().get("rule"));
        assertFalse(o.attributes().containsKey("nullValue"), "null attribute value ignored");
        assertTrue(o.createdAt() > 0 && o.updatedAt() == o.createdAt());
        assertEquals(0, o.closedAt());
        assertFalse(o.isClosed());
    }

    @Test
    void withStatusTerminalSetsClosedAtAndAttributesAreImmutable() {
        OperationalObject o = OperationalObject.builder(ObjectType.ALERT).status("OPEN").attr("k", "v").build();
        long t = o.createdAt() + 5;
        OperationalObject acked = o.withStatus("ACKNOWLEDGED", t, false);
        assertEquals("ACKNOWLEDGED", acked.status());
        assertEquals(0, acked.closedAt(), "non-terminal keeps closedAt unset");
        assertEquals(t, acked.updatedAt());

        OperationalObject resolved = acked.withStatus("RESOLVED", t + 1, true);
        assertEquals(t + 1, resolved.closedAt());
        assertTrue(resolved.isClosed());

        assertThrows(UnsupportedOperationException.class, () -> o.attributes().put("x", "y"));
    }

    @Test
    void requiredFieldsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new OperationalObject(
                "", ObjectType.ALERT, "t", "d", "OPEN", null, null, null, null, null, Map.of(), 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new OperationalObject(
                "id", null, "t", "d", "OPEN", null, null, null, null, null, Map.of(), 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new OperationalObject(
                "id", ObjectType.ALERT, "t", "d", "  ", null, null, null, null, null, Map.of(), 1, 1, 0));
    }

    @Test
    void objectTypeParseIsLenientThenStrict() {
        assertNull(ObjectType.of(null));
        assertNull(ObjectType.of("  "));
        assertEquals(ObjectType.ALERT, ObjectType.of("alert"));
        assertEquals(ObjectType.CASE, ObjectType.of(" Case "));
        assertThrows(IllegalArgumentException.class, () -> ObjectType.of("nope"));
    }

    @Test
    void queryMatchesAcrossDimensions() {
        OperationalObject o = OperationalObject.builder(ObjectType.ALERT)
                .status("OPEN").severity("WARNING").assignee("alice").correlationId("pipe")
                .title("disk full").build();
        assertTrue(ObjectQuery.builder().objectType(ObjectType.ALERT).status("open").build().matches(o));
        assertTrue(ObjectQuery.builder().severity("warning").assignee("ALICE").build().matches(o));
        assertTrue(ObjectQuery.builder().textContains("DISK").build().matches(o));
        assertFalse(ObjectQuery.builder().objectType(ObjectType.ISSUE).build().matches(o));
        assertFalse(ObjectQuery.builder().status("RESOLVED").build().matches(o));
        assertFalse(ObjectQuery.builder().correlationId("other").build().matches(o));
    }

    @Test
    void queryClampsPaging() {
        ObjectQuery zeroLimit = new ObjectQuery(null, null, null, null, null, null, null, 0, -5);
        assertEquals(ObjectQuery.DEFAULT_LIMIT, zeroLimit.limit());
        assertEquals(0, zeroLimit.offset());
        ObjectQuery huge = new ObjectQuery(null, null, null, null, null, null, null, 999_999, 0);
        assertEquals(ObjectQuery.MAX_LIMIT, huge.limit());
    }
}
