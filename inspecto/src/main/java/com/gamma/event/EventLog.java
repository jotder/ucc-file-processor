package com.gamma.event;

import com.gamma.metrics.MetricRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Process-wide entry point for emitting {@link Event}s — the event-engine analogue of
 * {@link MetricRegistry#global()}. Any layer (ingest worker, scheduler, the SLF4J capture appender,
 * the batch-event bridge) records facts through {@link #global()} without threading a store through
 * constructors; the Control API reads them back through the same store via {@code SourceService.events()}.
 *
 * <h3>Store swap with no lost startup events</h3>
 * The global instance starts with a small {@link InMemoryEventStore} so the very first log lines at
 * JVM start are captured. {@code SourceService} later {@linkplain #installStore(EventStore) installs}
 * the configured backend (e.g. {@code ParquetEventStore}); {@link #installStore} <b>drains</b> the
 * outgoing store's buffered events into the new one (oldest-first) so nothing emitted before the swap
 * is dropped.
 *
 * <h3>Must not log</h3>
 * {@link #emit} is on the SLF4J capture path, so it deliberately uses no SLF4J logger — a log call
 * here would recurse through the appender. All failures are swallowed (an observability sink must
 * never break the thing it observes). The appender additionally guards against re-entrancy.
 *
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public final class EventLog {

    private static final EventLog GLOBAL = new EventLog();

    /** The process-wide event log the service and Control API share. */
    public static EventLog global() {
        return GLOBAL;
    }

    private final AtomicReference<EventStore> store = new AtomicReference<>(new InMemoryEventStore());

    /**
     * Optional live subscribers, invoked on every {@link #emit} <em>after</em> the event is stored. The
     * service tier registers a bridge here that promotes selected domain events (e.g. {@code SEQUENCE_GAP})
     * to managed objects — keeping that policy out of the lean engine core that emits the event. Copy-on-write
     * so emit never blocks a (rare) registration, and each subscriber call is individually guarded so one
     * misbehaving listener can't break the sink (or the log appender it may sit behind).
     */
    private final CopyOnWriteArrayList<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();

    private EventLog() {}

    /** Register a live subscriber invoked after each {@link #emit}. Idempotent-safe to pair with {@link #removeSubscriber}. */
    public void addSubscriber(Consumer<Event> subscriber) {
        if (subscriber != null) subscribers.add(subscriber);
    }

    /** Remove a previously {@linkplain #addSubscriber registered} subscriber (e.g. on service shutdown). */
    public void removeSubscriber(Consumer<Event> subscriber) {
        if (subscriber != null) subscribers.remove(subscriber);
    }

    /** The current backing store (for the read API / tests). */
    public EventStore store() {
        return store.get();
    }

    /**
     * Install {@code next} as the backing store, draining the previous store's retained events into it
     * (oldest-first) so startup events survive the swap. No-op if {@code next} is {@code null} or the
     * current store.
     */
    public void installStore(EventStore next) {
        if (next == null) return;
        EventStore prev = store.getAndSet(next);
        if (prev == next || prev == null) return;
        try {
            List<Event> carry = prev.recent(Integer.MAX_VALUE);   // newest-first
            for (int i = carry.size() - 1; i >= 0; i--) next.append(carry.get(i));   // re-append oldest-first
        } catch (RuntimeException ignore) {
            // best effort — never block the swap
        }
    }

    /** Append one event and bump the {@code inspecto_events_total{level,type}} counter. Never throws. */
    public void emit(Event event) {
        if (event == null) return;
        try {
            store.get().append(event);
            MetricRegistry.global().inc("inspecto_events_total", "Operational events recorded",
                    Map.of("level", event.level().name(), "type", event.type()));
        } catch (Throwable t) {
            // Swallow: an event sink must not disturb the caller (which may be the log appender).
        }
        // Notify live subscribers after the event is durably recorded. Each is guarded independently so a
        // subscriber fault (or its own re-entrant emit) can never break this sink.
        for (Consumer<Event> s : subscribers) {
            try {
                s.accept(event);
            } catch (Throwable ignore) {
                // best effort — a listener must never break the thing it observes
            }
        }
    }

    /** Convenience: build and emit a domain event from a populated builder. */
    public void emit(Event.Builder builder) {
        if (builder != null) emit(builder.build());
    }

    /** Flush the backing store's buffer to durable storage, if any. Never throws. */
    public void flush() {
        try { store.get().flush(); } catch (Throwable ignore) { /* best effort */ }
    }
}
