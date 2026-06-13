package com.gamma.event;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the dependency-free event core: {@link EventLevel}, {@link Event},
 * {@link EventQuery} matching, and the {@link InMemoryEventStore} ring (append/recent/query/paging).
 */
class EventCoreTest {

    private static Event evt(long ts, EventLevel level, String type, String pipeline, String corr, String msg) {
        return new Event(null, ts, level, type, "src", pipeline, corr, msg, Map.of());
    }

    @Test
    void levelCaptureThresholdExcludesDebugAndTrace() {
        assertFalse(EventLevel.TRACE.isCaptured());
        assertFalse(EventLevel.DEBUG.isCaptured());
        assertTrue(EventLevel.INFO.isCaptured());
        assertTrue(EventLevel.WARN.isCaptured());
        assertTrue(EventLevel.ERROR.isCaptured());
        assertTrue(EventLevel.ERROR.atLeast(EventLevel.INFO));
        assertFalse(EventLevel.INFO.atLeast(EventLevel.WARN));
        assertEquals(EventLevel.WARN, EventLevel.parse("warning"));
        assertEquals(EventLevel.ERROR, EventLevel.parse("SEVERE"));
        assertEquals(EventLevel.INFO, EventLevel.parse("nonsense"));   // lenient fallback
    }

    @Test
    void eventFillsDefaultsAndRendersStableMap() {
        Event e = Event.builder(EventType.JOB_STARTED).source("com.gamma.X").message("hi")
                .attr("rows", 5).attr("skip", null).build();
        assertNotNull(e.eventId(), "id auto-generated");
        assertEquals(EventLevel.INFO, e.level());
        assertEquals(Map.of("rows", "5"), e.attributes(), "null attr ignored; values stringified");
        assertThrows(UnsupportedOperationException.class, () -> e.attributes().put("x", "y"),
                "attributes immutable");

        Map<String, Object> m = e.toMap();
        assertEquals(List.of("eventId", "ts", "timestamp", "level", "type", "source", "pipeline",
                "correlationId", "message", "attributes"), List.copyOf(m.keySet()));
        assertEquals("JOB_STARTED", m.get("type"));
        assertTrue(((String) m.get("timestamp")).endsWith("Z"), "ISO-8601 UTC timestamp");
    }

    @Test
    void queryMatchesEveryDimension() {
        Event e = evt(1_000L, EventLevel.WARN, EventType.BATCH_FAILED, "PipeA", "batch-7", "disk full on node3");
        assertTrue(EventQuery.builder().minLevel(EventLevel.INFO).build().matches(e));
        assertFalse(EventQuery.builder().minLevel(EventLevel.ERROR).build().matches(e), "WARN < ERROR");
        assertTrue(EventQuery.builder().type("batch_failed").build().matches(e), "type case-insensitive");
        assertTrue(EventQuery.builder().pipeline("pipea").build().matches(e), "pipeline case-insensitive");
        assertFalse(EventQuery.builder().pipeline("other").build().matches(e));
        assertTrue(EventQuery.builder().correlationId("batch-7").build().matches(e));
        assertTrue(EventQuery.builder().textContains("DISK").build().matches(e), "text case-insensitive");
        assertFalse(EventQuery.builder().textContains("missing").build().matches(e));
        assertTrue(EventQuery.builder().from(500L).to(2000L).build().matches(e));
        assertFalse(EventQuery.builder().from(2000L).build().matches(e), "before window");
    }

    @Test
    void inMemoryStoreIsNewestFirstAndCapacityBounded() {
        InMemoryEventStore store = new InMemoryEventStore(3);
        for (int i = 0; i < 5; i++) store.append(evt(i, EventLevel.INFO, EventType.LOG, "p", null, "m" + i));
        assertEquals(3, store.size(), "ring bounded to capacity");
        List<Event> recent = store.recent(10);
        assertEquals("m4", recent.get(0).message(), "newest first");
        assertEquals("m2", recent.get(2).message(), "oldest retained is m2 (m0,m1 evicted)");
    }

    @Test
    void inMemoryStoreQueryFiltersAndPages() {
        InMemoryEventStore store = new InMemoryEventStore(100);
        for (int i = 0; i < 10; i++) {
            EventLevel lvl = (i % 2 == 0) ? EventLevel.INFO : EventLevel.ERROR;
            store.append(evt(i, lvl, EventType.LOG, "p", null, "m" + i));
        }
        List<Event> errors = store.query(EventQuery.builder().minLevel(EventLevel.ERROR).limit(100).build());
        assertEquals(5, errors.size(), "only ERROR rows");
        assertTrue(errors.stream().allMatch(e -> e.level() == EventLevel.ERROR));

        // paging: newest-first, skip 2, take 3
        List<Event> page = store.query(EventQuery.builder().limit(3).offset(2).build());
        assertEquals(3, page.size());
        assertEquals("m7", page.get(0).message(), "m9,m8 skipped → m7 first");
    }
}
