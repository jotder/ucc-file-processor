package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DeletionFence} (T25, §3.8 rule 4): a delete races only an <em>active</em> producer/consumer of a
 * <em>resting</em> store; an idle store (quiet window) or a non-resting {@code sink.view} is never a conflict.
 */
class DeletionFenceTest {

    /** A flow that produces {@code store} via a sink of the given subtype. */
    private static PipelineGraph producer(String name, String store, String sinkType) {
        return new PipelineGraph(name, true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        new PipelineNode("sink", sinkType, store, null, Map.of(PipelineStores.CONFIG_STORE, store), null)),
                List.of(PipelineEdge.data("acq", "sink")));
    }

    /** A flow that consumes {@code store} at rest. */
    private static PipelineGraph consumer(String name, String store) {
        return new PipelineGraph(name, true,
                List.of(new PipelineNode("src", "transform.map", "read", null,
                        Map.of(PipelineStores.CONFIG_SOURCE_STORE, store), null)),
                List.of());
    }

    @Test
    void conflictWhenAProducerIsRunning() {
        List<PipelineGraph> flows = List.of(producer("orders_etl", "orders", "sink.persistent"));
        List<DeletionFence.Conflict> c = DeletionFence.check(List.of("orders"), flows, Set.of("orders_etl"));
        assertEquals(1, c.size());
        assertEquals("orders", c.get(0).store());
        assertEquals(List.of("orders_etl"), c.get(0).activeProducers());
        assertTrue(c.get(0).activeConsumers().isEmpty());
    }

    @Test
    void conflictWhenOnlyAConsumerIsRunning() {
        List<PipelineGraph> flows = List.of(
                producer("orders_etl", "orders", "sink.persistent"),
                consumer("orders_rollup", "orders"));
        // the producer is idle, but a consumer is reading → still a conflict
        List<DeletionFence.Conflict> c = DeletionFence.check(List.of("orders"), flows, Set.of("orders_rollup"));
        assertEquals(1, c.size());
        assertEquals(List.of("orders_rollup"), c.get(0).activeConsumers());
        assertTrue(c.get(0).activeProducers().isEmpty());
    }

    @Test
    void clearInAQuietWindow() {
        List<PipelineGraph> flows = List.of(
                producer("orders_etl", "orders", "sink.persistent"),
                consumer("orders_rollup", "orders"));
        // nothing running → the delete is in a quiet window → no conflict
        assertTrue(DeletionFence.check(List.of("orders"), flows, Set.of()).isEmpty());
    }

    @Test
    void viewStoreIsNeverADeletionHazard() {
        // a sink.view persists nothing on disk, so there is nothing to delete and no race
        List<PipelineGraph> flows = List.of(producer("kpi_flow", "active_subs", "sink.view"));
        assertTrue(DeletionFence.check(List.of("active_subs"), flows, Set.of("kpi_flow")).isEmpty());
    }

    @Test
    void unknownStoreIsClear() {
        List<PipelineGraph> flows = List.of(producer("orders_etl", "orders", "sink.persistent"));
        assertTrue(DeletionFence.check(List.of("nonexistent"), flows, Set.of("orders_etl")).isEmpty());
    }
}
