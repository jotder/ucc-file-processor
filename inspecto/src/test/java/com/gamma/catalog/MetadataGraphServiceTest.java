package com.gamma.catalog;

import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MetadataGraphService} structural assembly, edge derivation, ref resolution,
 * caching/invalidation, and filterable BFS traversal (P3). Uses a real single-schema pipeline
 * (via {@link PipelineConfigBatchTest#writePipeline}) plus directly-constructed enrichment and
 * semantic models through a hand-built {@link ConfigSource}.
 */
class MetadataGraphServiceTest {

    /** Mutable config source so we can test cache rebuild on invalidate(). */
    private static final class Fixture implements ConfigSource {
        final List<PipelineConfig> pipelines = new ArrayList<>();
        final List<EnrichmentConfig> enrichments = new ArrayList<>();
        final List<SemanticModel> semantics = new ArrayList<>();
        public List<PipelineConfig> pipelines() { return pipelines; }
        public List<EnrichmentConfig> enrichments() { return enrichments; }
        public List<SemanticModel> semantics() { return semantics; }
    }

    private static EnrichmentConfig enrichment(String name, String upstreamPipeline) {
        return new EnrichmentConfig(name,
                new EnrichmentConfig.Input("database/x", "PARQUET", List.of("event_type", "year")),
                List.of(new EnrichmentConfig.Reference("region_dim", "ref/region.parquet", "PARQUET")),
                new EnrichmentConfig.Output("reports/x", "PARQUET", "snappy", List.of("year")),
                "SELECT 1",
                new EnrichmentConfig.Triggers(upstreamPipeline, 0L));
    }

    private static SemanticModel semantics() {
        var arpu = new SemanticModel.KpiMeta("daily_count", "Count per day", "day",
                List.of("mini_etl/mini", "MINI_DAILY"), List.of("ID"));
        var report = new SemanticModel.ReportMeta("r1", "A daily report", List.of("daily_count"));
        var table = new SemanticModel.TableMeta("mini_etl/mini", "One row per mini event", "EVENT_DATE");
        return new SemanticModel("sem",
                java.util.Map.of("mini_etl/mini", table),
                java.util.Map.of("daily_count", arpu),
                java.util.Map.of("r1", report),
                new SemanticModel.DomainNotes("USD", "UTC", List.of("excludes tax")));
    }

    private Fixture fullFixture(Path dir) throws Exception {
        Fixture f = new Fixture();
        f.pipelines.add(PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString()));
        f.enrichments.add(enrichment("MINI_DAILY", "MINI_ETL"));
        f.semantics.add(semantics());
        return f;
    }

    private static Set<String> ids(List<MetadataNode> nodes) {
        return nodes.stream().map(MetadataNode::id).collect(Collectors.toSet());
    }

    private static boolean hasEdge(MetadataGraph g, String from, String to, EdgeKind kind) {
        return g.edges().stream().anyMatch(e -> e.from().equals(from) && e.to().equals(to) && e.kind() == kind);
    }

    @Test
    void assemblesAllNodeKinds(@TempDir Path dir) throws Exception {
        MetadataGraphService svc = new MetadataGraphService(fullFixture(dir));
        Set<String> ids = ids(svc.structural().nodes());

        assertTrue(ids.contains("stream:mini_etl"), ids.toString());
        assertTrue(ids.contains("schema:mini_etl/mini"));
        assertTrue(ids.contains("col:mini_etl/mini/ID"));
        assertTrue(ids.contains("col:mini_etl/mini/EVENT_DATE"));
        assertTrue(ids.contains("event:mini_etl/mini"));
        assertTrue(ids.contains("xform:MINI_DAILY"));
        assertTrue(ids.contains("ref:MINI_DAILY/region_dim"));
        assertTrue(ids.contains("kpi:daily_count"));
        assertTrue(ids.contains("report:r1"));

        assertEquals(NodeKind.TABLE, svc.node("event:mini_etl/mini").kind());
        assertEquals(2, svc.tables().size(), "1 event table + 1 transformed table");
    }

    @Test
    void derivesEverySpineEdge(@TempDir Path dir) throws Exception {
        MetadataGraph g = new MetadataGraphService(fullFixture(dir)).structural();

        assertTrue(hasEdge(g, "stream:mini_etl", "event:mini_etl/mini", EdgeKind.EMITS));
        assertTrue(hasEdge(g, "stream:mini_etl", "schema:mini_etl/mini", EdgeKind.DECLARES));
        assertTrue(hasEdge(g, "schema:mini_etl/mini", "col:mini_etl/mini/ID", EdgeKind.DESCRIBES));
        assertTrue(hasEdge(g, "schema:mini_etl/mini", "event:mini_etl/mini", EdgeKind.MATERIALIZES));
        assertTrue(hasEdge(g, "event:mini_etl/mini", "xform:MINI_DAILY", EdgeKind.FEEDS));
        assertTrue(hasEdge(g, "ref:MINI_DAILY/region_dim", "xform:MINI_DAILY", EdgeKind.JOINS_INTO));
        // bare refs resolved: kpi inputs -> event table + transformed table
        assertTrue(hasEdge(g, "kpi:daily_count", "event:mini_etl/mini", EdgeKind.COMPUTED_FROM));
        assertTrue(hasEdge(g, "kpi:daily_count", "xform:MINI_DAILY", EdgeKind.COMPUTED_FROM));
        assertTrue(hasEdge(g, "report:r1", "kpi:daily_count", EdgeKind.CONSUMES));
    }

    @Test
    void semanticTableDescriptionAttachesToEventTable(@TempDir Path dir) throws Exception {
        MetadataGraphService svc = new MetadataGraphService(fullFixture(dir));
        MetadataNode ev = svc.node("event:mini_etl/mini");
        assertEquals("One row per mini event", ev.description().text());
        assertEquals(Provenance.MANUAL, ev.description().provenance());
        assertEquals("EVENT_DATE", ev.attrs().get("grainNote"));
    }

    @Test
    void feedsLinkedByInputDatabaseWhenNoTrigger(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture();
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        f.pipelines.add(cfg);
        // enrichment with NO trigger, but input.database == the pipeline's db root -> FEEDS via fallback
        f.enrichments.add(new EnrichmentConfig("NOTRIG",
                new EnrichmentConfig.Input(cfg.dirs().database(), "CSV", List.of("EVENT_DATE")),
                List.of(),
                new EnrichmentConfig.Output("reports/n", "CSV", null, List.of("EVENT_DATE")),
                "SELECT 1"));
        MetadataGraph g = new MetadataGraphService(f).structural();
        assertTrue(hasEdge(g, "event:mini_etl/mini", "xform:NOTRIG", EdgeKind.FEEDS),
                "FEEDS resolved by matching input.database to the pipeline db root");
    }

    @Test
    void traverseFromKpiReachesSourceAndColumns(@TempDir Path dir) throws Exception {
        MetadataGraphService svc = new MetadataGraphService(fullFixture(dir));
        MetadataGraph sub = svc.traverse("kpi:daily_count", 6, MetadataGraphService.Direction.BOTH,
                null, null, false);
        Set<String> ids = ids(sub.nodes());
        assertTrue(ids.contains("stream:mini_etl"), "KPI walks down to the source: " + ids);
        assertTrue(ids.contains("col:mini_etl/mini/ID"), "and to its columns: " + ids);
    }

    @Test
    void traverseRespectsDepthDirectionAndFilters(@TempDir Path dir) throws Exception {
        MetadataGraphService svc = new MetadataGraphService(fullFixture(dir));

        // depth 1 OUT from source -> only directly-emitted/declared neighbours
        MetadataGraph d1 = svc.traverse("stream:mini_etl", 1, MetadataGraphService.Direction.OUT,
                null, null, false);
        Set<String> d1ids = ids(d1.nodes());
        assertTrue(d1ids.contains("event:mini_etl/mini"));
        assertTrue(d1ids.contains("schema:mini_etl/mini"));
        assertFalse(d1ids.contains("col:mini_etl/mini/ID"), "columns are 2 hops away");

        // edgeKinds filter: only EMITS from source -> schema not reached
        MetadataGraph emitsOnly = svc.traverse("stream:mini_etl", 3, MetadataGraphService.Direction.OUT,
                null, EnumSet.of(EdgeKind.EMITS), false);
        assertFalse(ids(emitsOnly.nodes()).contains("schema:mini_etl/mini"));

        // node-kinds filter: keep only COLUMN nodes in the whole graph
        MetadataGraph colsOnly = svc.traverse(null, 0, MetadataGraphService.Direction.BOTH,
                EnumSet.of(NodeKind.COLUMN), null, false);
        assertTrue(colsOnly.nodes().stream().allMatch(n -> n.kind() == NodeKind.COLUMN));
        assertEquals(3, colsOnly.nodes().size());
    }

    @Test
    void cacheIsStableUntilInvalidated(@TempDir Path dir) throws Exception {
        Fixture f = fullFixture(dir);
        MetadataGraphService svc = new MetadataGraphService(f);
        MetadataGraph first = svc.structural();
        assertSame(first, svc.structural(), "structural graph is cached");

        // mutate config + invalidate -> rebuild reflects the change
        f.enrichments.add(enrichment("SECOND", "MINI_ETL"));
        assertFalse(ids(svc.structural().nodes()).contains("xform:SECOND"), "stale cache unchanged");
        svc.invalidate();
        assertTrue(ids(svc.structural().nodes()).contains("xform:SECOND"), "rebuild picks up new config");
    }

    @Test
    void overlayIsNoneWithoutAnOverlaySource(@TempDir Path dir) throws Exception {
        MetadataGraphService svc = new MetadataGraphService(fullFixture(dir));
        MetadataNode n = svc.hydrated("event:mini_etl/mini");
        assertNotNull(n.overlay());
        assertEquals("UNKNOWN", n.overlay().latestStatus());
    }

    @Test
    void unknownTraversalRootYieldsEmpty(@TempDir Path dir) throws Exception {
        MetadataGraphService svc = new MetadataGraphService(fullFixture(dir));
        assertEquals(0, svc.traverse("kpi:nope", 3, MetadataGraphService.Direction.BOTH, null, null, false)
                .nodes().size());
    }

    // ── produces:reference (v5.1.0) — standalone Reference Datasets + by-name binding ──

    /** A minimal {@code produces: reference} pipeline (dimension origin). */
    private static PipelineConfig referencePipeline(Path dir) throws Exception {
        Path toon = dir.resolve("region_dim_pipeline.toon");
        Files.writeString(toon, """
                name: REGION_DIM
                produces: reference
                version: 1
                dirs:
                  poll: %s/ref_inbox
                  database: %s/refdb
                output:
                  format: CSV
                processing:
                  threads: 1
                """.formatted(dir.toString().replace("\\", "/"), dir.toString().replace("\\", "/")));
        return PipelineConfig.load(toon.toString());
    }

    private static EnrichmentConfig byNameEnrichment(String name) {
        return new EnrichmentConfig(name,
                new EnrichmentConfig.Input("database/y", "PARQUET", List.of("year")),
                List.of(new EnrichmentConfig.Reference("region_dim", null, "CSV", "region_dim")),
                new EnrichmentConfig.Output("reports/y", "PARQUET", null, List.of("year")),
                "SELECT 1");
    }

    @Test
    void producesReferenceRegistersStandaloneReferenceDataset(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture();
        f.pipelines.add(referencePipeline(dir));
        MetadataGraphService svc = new MetadataGraphService(f);
        Set<String> ids = ids(svc.structural().nodes());

        assertTrue(ids.contains("ref:region_dim"), ids.toString());
        assertFalse(ids.contains("stream:region_dim"), "a reference pipeline is not a Stream origin");
        assertEquals(NodeKind.REFERENCE_DATASET, svc.node("ref:region_dim").kind());
        assertEquals("CSV", svc.node("ref:region_dim").attrs().get("format"));
        assertEquals("region_dim", svc.node("ref:region_dim").attrs().get("pipeline"));
        // Lifecycle for the Catalog References tab (Draft/Live) — same column as Streams.
        assertEquals(false, svc.node("ref:region_dim").attrs().get("active"));
    }

    @Test
    void byNameReferenceBindsToTheProducedNode(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture();
        f.pipelines.add(referencePipeline(dir));
        f.enrichments.add(byNameEnrichment("BY_NAME"));
        MetadataGraph g = new MetadataGraphService(f).structural();

        assertTrue(hasEdge(g, "ref:region_dim", "xform:BY_NAME", EdgeKind.JOINS_INTO),
                "by-name ref binds to the produced node");
        assertFalse(ids(g.nodes()).contains("ref:BY_NAME/region_dim"),
                "no enrichment-scoped duplicate when the produced node exists");
    }

    @Test
    void byNameReferenceFallsBackToScopedNodeWhenPipelineMissing(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture();
        f.enrichments.add(byNameEnrichment("ORPHAN"));
        MetadataGraphService svc = new MetadataGraphService(f);
        MetadataGraph g = svc.structural();

        assertTrue(ids(g.nodes()).contains("ref:ORPHAN/region_dim"), "scoped fallback node created");
        assertEquals("region_dim", svc.node("ref:ORPHAN/region_dim").attrs().get("ref"),
                "unresolved by-name declaration recorded");
        assertTrue(hasEdge(g, "ref:ORPHAN/region_dim", "xform:ORPHAN", EdgeKind.JOINS_INTO));
    }
}
