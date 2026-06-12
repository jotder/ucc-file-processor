package com.gamma.service;

import com.gamma.etl.BatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process publish/subscribe bus for {@link BatchEvent}s — the trigger backbone
 * of the service. The ingest path emits a committed-batch event via the {@link #sink()}
 * {@link Consumer} (handed to {@code SourceProcessor.run}); subscribers (e.g. the
 * Stage-2 enrichment trigger in M2) react.
 *
 * <p>Synchronous, fan-out delivery: a listener that throws is logged and skipped so
 * one bad subscriber can't drop the event for the others. Listeners run on the
 * publishing thread — keep them quick or hand off to their own executor.
 */
public final class BatchEventBus {

    private static final Logger log = LoggerFactory.getLogger(BatchEventBus.class);

    private final List<Consumer<BatchEvent>> listeners = new CopyOnWriteArrayList<>();

    /** Register a listener. Thread-safe; may be called while the bus is in use. */
    public void subscribe(Consumer<BatchEvent> listener) {
        listeners.add(listener);
    }

    /** A sink to hand to the ingest layer (e.g. {@code SourceProcessor.run(cfg, bus.sink())}). */
    public Consumer<BatchEvent> sink() {
        return this::publish;
    }

    /** Deliver {@code event} to every subscriber; isolate per-listener failures. */
    public void publish(BatchEvent event) {
        for (Consumer<BatchEvent> l : listeners) {
            try {
                l.accept(event);
            } catch (Exception e) {
                log.error("Batch-event listener failed for batch {} (pipeline {})",
                        event.batchId(), event.pipeline(), e);
            }
        }
    }

    /** Current subscriber count (for diagnostics/tests). */
    public int listenerCount() {
        return listeners.size();
    }
}
