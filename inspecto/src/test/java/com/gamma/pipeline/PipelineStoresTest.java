package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PipelineStores}: the store-name superimposition that lets the system derive how a job/enrichment
 * is layered over a pipeline's output from config/metadata alone (doc §3.8/§15 T4) — no on_pipeline
 * name-coupling.
 */
class PipelineStoresTest {

    @Test
    void producedConsumedAndSuperimposeByStoreName() {
        // pipeline produces "cdr_events"; its quarantine sink declares no store (excluded)
        PipelineGraph pipeline = new PipelineGraph("CDR_ETL", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("sink", "sink.persistent", Map.of(PipelineStores.CONFIG_STORE, "cdr_events")),
                        PipelineNode.of("quar", "sink.persistent", Map.of("dir", "/q"))),
                List.of(PipelineEdge.data("acq", "sink")));

        // a downstream job consumes "cdr_events" at rest and produces "cdr_kpi"
        PipelineGraph job = new PipelineGraph("CDR_DAILY_KPI", false,
                List.of(PipelineNode.of("src", "acquisition", Map.of(PipelineStores.CONFIG_SOURCE_STORE, "cdr_events")),
                        PipelineNode.of("sink", "sink.persistent", Map.of(PipelineStores.CONFIG_STORE, "cdr_kpi"))),
                List.of(PipelineEdge.data("src", "sink")));

        assertEquals(Set.of("cdr_events"), PipelineStores.produced(pipeline));   // quarantine (no store) excluded
        assertEquals(Set.of("cdr_kpi"), PipelineStores.produced(job));
        assertEquals(Set.of("cdr_events"), PipelineStores.consumed(job));
        assertTrue(PipelineStores.consumed(pipeline).isEmpty());

        List<PipelineStores.Link> links = PipelineStores.superimpose(List.of(pipeline, job));
        assertEquals(List.of(new PipelineStores.Link("CDR_ETL", "cdr_events", "CDR_DAILY_KPI")), links);
    }

    @Test
    void aFlowIsNeverLinkedToItself() {
        PipelineGraph g = new PipelineGraph("X", false,
                List.of(PipelineNode.of("s", "sink.persistent", Map.of(PipelineStores.CONFIG_STORE, "t")),
                        PipelineNode.of("r", "acquisition", Map.of(PipelineStores.CONFIG_SOURCE_STORE, "t"))),
                List.of());
        assertTrue(PipelineStores.superimpose(List.of(g)).isEmpty());
    }

    @Test
    void viewSinkProducesANonPersistentStoreThatConsumersSuperimposeOver() {
        // a pipeline exposes a non-persistent VIEW "active_subs"; a downstream alert API consumes it
        PipelineGraph pipeline = new PipelineGraph("SUBS_ETL", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        new PipelineNode("v", "sink.view", "Active subscribers", null,
                                Map.of(PipelineStores.CONFIG_STORE, "active_subs"), null)),
                List.of(PipelineEdge.data("acq", "v")));
        PipelineGraph alert = new PipelineGraph("SUBS_DROP_ALERT", false,
                List.of(PipelineNode.of("src", "acquisition", Map.of(PipelineStores.CONFIG_SOURCE_STORE, "active_subs"))),
                List.of());

        // the view is a producer by store name (superimposition is persistence-agnostic) ...
        assertEquals(Set.of("active_subs"), PipelineStores.produced(pipeline));
        assertEquals(List.of(new PipelineStores.Link("SUBS_ETL", "active_subs", "SUBS_DROP_ALERT")),
                PipelineStores.superimpose(List.of(pipeline, alert)));

        // ... but it does NOT rest on disk, so the deletion fence (§3.8) sees no storage hazard
        PipelineStores.Produced p = PipelineStores.producedStores(pipeline).get(0);
        assertEquals("sink.view", p.sinkType());
        assertFalse(p.restsOnDisk());
    }
}
