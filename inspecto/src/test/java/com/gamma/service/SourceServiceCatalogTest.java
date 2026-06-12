package com.gamma.service;

import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.NodeKind;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.JobConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies SourceService owns and wires the metadata catalog end-to-end (P5). */
class SourceServiceCatalogTest {

    private static SemanticModel semantics() {
        var kpi = new SemanticModel.KpiMeta("daily", "Daily count", "day", List.of("mini_etl/mini"), List.of());
        return new SemanticModel("sem", Map.of(), Map.of("daily", kpi), Map.of(),
                SemanticModel.DomainNotes.EMPTY);
    }

    @Test
    void catalogIsBuiltFromRegistryAndSemantics(@TempDir Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        try (SourceService svc = new SourceService(List.of(pipe), List.of(), List.of(),
                List.of(semantics()), 60, 1, null)) {
            MetadataGraphService catalog = svc.catalog();
            assertNotNull(catalog);

            // structural graph derived from the registered pipeline + the semantic model
            assertNotNull(catalog.node("source:mini_etl"));
            assertNotNull(catalog.node("event:mini_etl/mini"));
            assertNotNull(catalog.node("kpi:daily"));
            assertEquals(1, catalog.nodesOfKind(NodeKind.EVENT_TABLE).size());

            // KPI bare-ref resolved down to the event table
            assertTrue(catalog.traverse("kpi:daily", 5, MetadataGraphService.Direction.BOTH, null, null, false)
                    .nodes().stream().anyMatch(n -> n.id().equals("source:mini_etl")));

            // overlay wired: no data committed yet -> NO_DATA (not a crash)
            assertEquals("NO_DATA", catalog.hydrated("event:mini_etl/mini").overlay().latestStatus());

            // domain merge works
            assertNotNull(catalog.domain().get("notes"));
        }
    }
}
