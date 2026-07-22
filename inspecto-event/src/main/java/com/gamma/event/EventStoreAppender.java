package com.gamma.event;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logback appender that turns every captured SLF4J log record into an {@link Event} on
 * {@link EventLog#global()} — the mechanism behind the requirement's "all events except DEBUG are
 * logged into the system". It is wired in {@code src/main/resources/logback.xml} alongside the normal
 * console appender, with a {@code ThresholdFilter} at {@code INFO}, so {@code TRACE}/{@code DEBUG}
 * records are never captured (console-only); {@code INFO}/{@code WARN}/{@code ERROR} become events.
 *
 * <h3>Why logback (and not slf4j-simple)</h3>
 * slf4j-simple has no appender SPI, so it cannot tee log records into a sink. Swapping the binding to
 * logback-classic (4.2.0) is what makes automatic, structured capture possible without rewriting the
 * hundreds of existing {@code log.info/warn/error} call sites.
 *
 * <h3>Re-entrancy guard</h3>
 * The event store (e.g. {@code ParquetEventStore}) may itself log — which would re-enter this appender
 * and recurse. A per-thread {@code inside} flag short-circuits the nested call, so a store is free to
 * log normally. {@link EventLog#emit} additionally swallows all failures.
 *
 * @since 4.2.0
 */
public final class EventStoreAppender extends AppenderBase<ILoggingEvent> {

    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Override
    protected void append(ILoggingEvent log) {
        if (Boolean.TRUE.equals(INSIDE.get())) return;          // store logged while we were emitting
        EventLevel level = EventLevel.parse(log.getLevel().toString());
        if (!level.isCaptured()) return;                        // DEBUG/TRACE → console only
        INSIDE.set(Boolean.TRUE);
        try {
            Map<String, String> mdc = log.getMDCPropertyMap();
            String pipeline = mdc == null ? null : mdc.get("pipeline");
            String correlationId = mdc == null ? null : mdc.get("correlationId");
            // Route to the owning space's log (MDC "space"); falls back to the global log when unscoped.
            EventLog.current().emit(Event.log(log.getTimeStamp(), level, log.getLoggerName(),
                    log.getFormattedMessage(), pipeline, correlationId, attributes(log, mdc)));
        } finally {
            INSIDE.set(Boolean.FALSE);
        }
    }

    /** Carry the thread, any extra MDC keys, and a compact exception summary (no full stack trace). */
    private static Map<String, String> attributes(ILoggingEvent log, Map<String, String> mdc) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("thread", log.getThreadName());
        if (mdc != null) {
            for (Map.Entry<String, String> e : mdc.entrySet()) {
                // pipeline/correlationId become first-class event fields; space is a routing key — none repeat in attrs.
                if (!"pipeline".equals(e.getKey()) && !"correlationId".equals(e.getKey())
                        && !EventLog.SPACE_MDC_KEY.equals(e.getKey())) {
                    attrs.put(e.getKey(), e.getValue());
                }
            }
        }
        IThrowableProxy t = log.getThrowableProxy();
        if (t != null) {
            attrs.put("exception", t.getClassName());
            if (t.getMessage() != null) attrs.put("exceptionMessage", t.getMessage());
        }
        return attrs;
    }
}
