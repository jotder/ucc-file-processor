package com.gamma.event;

import com.gamma.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link EventLog} facade (store swap with startup-event draining + the
 * {@code inspecto_events_total} metric) and end-to-end SLF4J capture through the
 * {@link EventStoreAppender} configured in {@code logback.xml} — the "everything except DEBUG" rule.
 */
class EventLogAndAppenderTest {

    @Test
    void installStoreDrainsRetainedEventsAndEmitBumpsMetric() {
        InMemoryEventStore first = new InMemoryEventStore(100);
        EventLog.global().installStore(first);
        EventLog.global().emit(Event.builder(EventType.SERVICE_STARTED).message("early-startup").build());

        InMemoryEventStore second = new InMemoryEventStore(100);
        EventLog.global().installStore(second);   // swap — early event must carry over
        assertSame(second, EventLog.global().store());
        assertEquals(1, second.recent(1000).stream().filter(e -> "early-startup".equals(e.message())).count(),
                "startup event drained into the newly installed store");

        EventLog.global().emit(Event.builder(EventType.LOG).message("metered").build());
        assertTrue(MetricRegistry.global().scrape().contains("inspecto_events_total"),
                "emit increments the events counter");
    }

    @Test
    void slf4jCaptureRecordsInfoAndAboveButNotDebug() {
        InMemoryEventStore store = new InMemoryEventStore(1000);
        EventLog.global().installStore(store);

        Logger log = LoggerFactory.getLogger("test.capture.Marker");
        log.debug("DBGMARK-should-not-capture");   // below threshold → console only
        log.info("INFMARK");
        log.warn("WRNMARK");
        log.error("ERRMARK");

        List<Event> captured = store.query(EventQuery.builder().textContains("MARK").limit(100).build());
        List<String> messages = captured.stream().map(Event::message).toList();
        assertTrue(messages.contains("INFMARK"), "INFO captured");
        assertTrue(messages.contains("WRNMARK"), "WARN captured");
        assertTrue(messages.contains("ERRMARK"), "ERROR captured");
        assertTrue(store.query(EventQuery.builder().textContains("DBGMARK").limit(100).build()).isEmpty(),
                "DEBUG must not be captured into the event store");

        Event err = captured.stream().filter(e -> "ERRMARK".equals(e.message())).findFirst().orElseThrow();
        assertEquals(EventLevel.ERROR, err.level(), "log level mapped");
        assertEquals("test.capture.Marker", err.source(), "logger name → source");
        assertEquals(EventType.LOG, err.type());
        assertEquals(Thread.currentThread().getName(), err.attributes().get("thread"));
    }

    @Test
    void capturesExceptionSummaryWithoutFullStackTrace() {
        InMemoryEventStore store = new InMemoryEventStore(1000);
        EventLog.global().installStore(store);
        LoggerFactory.getLogger("test.capture.Ex")
                .error("EXMARK boom", new IllegalStateException("kaboom"));
        Event e = store.query(EventQuery.builder().textContains("EXMARK").limit(10).build()).get(0);
        assertEquals("java.lang.IllegalStateException", e.attributes().get("exception"));
        assertEquals("kaboom", e.attributes().get("exceptionMessage"));
    }
}
