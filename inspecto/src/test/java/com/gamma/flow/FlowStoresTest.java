package com.gamma.flow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FlowStores}: the store-name superimposition that lets the system derive how a job/enrichment
 * is layered over a pipeline's output from config/metadata alone (doc §3.8/§15 T4) — no on_pipeline
 * name-coupling.
 */
class FlowStoresTest {

    @Test
    void producedConsumedAndSuperimposeByStoreName() {
        // pipeline produces "cdr_events"; its quarantine sink declares no store (excluded)
        FlowGraph pipeline = new FlowGraph("CDR_ETL", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        FlowNode.of("sink", "sink.persistent", Map.of(FlowStores.CONFIG_STORE, "cdr_events")),
                        FlowNode.of("quar", "sink.persistent", Map.of("dir", "/q"))),
                List.of(FlowEdge.data("acq", "sink")));

        // a downstream job consumes "cdr_events" at rest and produces "cdr_kpi"
        FlowGraph job = new FlowGraph("CDR_DAILY_KPI", false,
                List.of(FlowNode.of("src", "acquisition", Map.of(FlowStores.CONFIG_SOURCE_STORE, "cdr_events")),
                        FlowNode.of("sink", "sink.persistent", Map.of(FlowStores.CONFIG_STORE, "cdr_kpi"))),
                List.of(FlowEdge.data("src", "sink")));

        assertEquals(Set.of("cdr_events"), FlowStores.produced(pipeline));   // quarantine (no store) excluded
        assertEquals(Set.of("cdr_kpi"), FlowStores.produced(job));
        assertEquals(Set.of("cdr_events"), FlowStores.consumed(job));
        assertTrue(FlowStores.consumed(pipeline).isEmpty());

        List<FlowStores.Link> links = FlowStores.superimpose(List.of(pipeline, job));
        assertEquals(List.of(new FlowStores.Link("CDR_ETL", "cdr_events", "CDR_DAILY_KPI")), links);
    }

    @Test
    void aFlowIsNeverLinkedToItself() {
        FlowGraph g = new FlowGraph("X", false,
                List.of(FlowNode.of("s", "sink.persistent", Map.of(FlowStores.CONFIG_STORE, "t")),
                        FlowNode.of("r", "acquisition", Map.of(FlowStores.CONFIG_SOURCE_STORE, "t"))),
                List.of());
        assertTrue(FlowStores.superimpose(List.of(g)).isEmpty());
    }

    @Test
    void viewSinkProducesANonPersistentStoreThatConsumersSuperimposeOver() {
        // a pipeline exposes a non-persistent VIEW "active_subs"; a downstream alert API consumes it
        FlowGraph pipeline = new FlowGraph("SUBS_ETL", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        new FlowNode("v", "sink.view", "Active subscribers", null,
                                Map.of(FlowStores.CONFIG_STORE, "active_subs"), null)),
                List.of(FlowEdge.data("acq", "v")));
        FlowGraph alert = new FlowGraph("SUBS_DROP_ALERT", false,
                List.of(FlowNode.of("src", "acquisition", Map.of(FlowStores.CONFIG_SOURCE_STORE, "active_subs"))),
                List.of());

        // the view is a producer by store name (superimposition is persistence-agnostic) ...
        assertEquals(Set.of("active_subs"), FlowStores.produced(pipeline));
        assertEquals(List.of(new FlowStores.Link("SUBS_ETL", "active_subs", "SUBS_DROP_ALERT")),
                FlowStores.superimpose(List.of(pipeline, alert)));

        // ... but it does NOT rest on disk, so the deletion fence (§3.8) sees no storage hazard
        FlowStores.Produced p = FlowStores.producedStores(pipeline).get(0);
        assertEquals("sink.view", p.sinkType());
        assertFalse(p.restsOnDisk());
    }
}
