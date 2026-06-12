package com.gamma.catalog;

import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.StatusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link CatalogOverlay} (P4): per-kind overlay projection from the audit reads. */
class CatalogOverlayTest {

    @SafeVarargs
    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** A StatusStore returning canned rows; ignores the cfg argument. */
    private static StatusStore store(Set<String> committed, List<Map<String, String>> batches,
                                     List<Map<String, String>> files, List<Map<String, String>> lineage) {
        return new StatusStore() {
            public Set<String> committedBatches(PipelineConfig c) { return committed; }
            public List<Map<String, String>> batches(PipelineConfig c) { return batches; }
            public List<Map<String, String>> files(PipelineConfig c) { return files; }
            public List<Map<String, String>> lineage(PipelineConfig c, String b) { return lineage; }
            public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
        };
    }

    private static MetadataNode src() {
        return new MetadataNode("source:mini_etl", NodeKind.SOURCE, "MINI_ETL", Description.EMPTY, Map.of());
    }

    @Test
    void sourceOverlaySummarisesStatusCompletenessAndLineage(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        StatusStore ss = store(
                Set.of("b1", "b2"),
                List.of(row("status", "SUCCESS", "end_time", "t1", "total_output_rows", "100", "total_output_bytes", "1000", "error", ""),
                        row("status", "SUCCESS", "end_time", "t2", "total_output_rows", "50", "total_output_bytes", "500", "error", "")),
                List.of(row("parsed_rows", "100", "error_rows", "2"),
                        row("parsed_rows", "50", "error_rows", "1")),
                List.of(row("partition", "year=2020/month=01"), row("partition", "year=2020/month=02")));
        CatalogOverlay ov = new CatalogOverlay(p -> p.equals("mini_etl") ? Optional.of(cfg) : Optional.empty(), ss, null);

        OperationalOverlay o = ov.overlayFor(src());
        assertEquals("SUCCESS", o.latestStatus());
        assertEquals("t2", o.latestRunTime());
        assertEquals(150, o.totalOutputRows());
        assertEquals(1500, o.totalOutputBytes());
        assertEquals(150, o.parsedRows());
        assertEquals(3, o.errorRows());
        assertTrue(o.dataProduced());
        assertEquals(2, o.lineageRefs().size());
    }

    @Test
    void noCommitsYieldsNoData(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        CatalogOverlay ov = new CatalogOverlay(p -> Optional.of(cfg),
                store(Set.of(), List.of(), List.of(), List.of()), null);
        OperationalOverlay o = ov.overlayFor(src());
        assertEquals("NO_DATA", o.latestStatus());
        assertFalse(o.dataProduced());
    }

    @Test
    void unknownPipelineYieldsNone() {
        CatalogOverlay ov = new CatalogOverlay(p -> Optional.empty(),
                store(Set.of(), List.of(), List.of(), List.of()), null);
        assertEquals(OperationalOverlay.NONE, ov.overlayFor(src()));
    }

    @Test
    void eventTableScopesLineageToItsEventType(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        StatusStore ss = store(Set.of("b1"),
                List.of(row("status", "SUCCESS", "end_time", "t", "total_output_rows", "9", "total_output_bytes", "9", "error", "")),
                List.of(row("parsed_rows", "9", "error_rows", "0")),
                List.of(row("partition", "event_type=CALL/year=2020"),
                        row("partition", "event_type=SMS/year=2020"),
                        row("partition", "event_type=CALL/year=2021")));
        CatalogOverlay ov = new CatalogOverlay(p -> Optional.of(cfg), ss, null);

        MetadataNode call = new MetadataNode("event:mini_etl/CALL", NodeKind.EVENT_TABLE, "CALL",
                Description.EMPTY, Map.of("source", "mini_etl", "eventType", "CALL"));
        OperationalOverlay o = ov.overlayFor(call);
        assertEquals(2, o.lineageRefs().size(), "only the two CALL partitions, not SMS");
        assertTrue(o.lineageRefs().stream().allMatch(p -> p.contains("event_type=CALL")));
    }

    @Test
    void transformedTableReadsStage2Audit() {
        CatalogOverlay.Stage2Reads stage2 = new CatalogOverlay.Stage2Reads() {
            public boolean hosts(String job) { return job.equals("DAILY"); }
            public List<Map<String, String>> runs(String job) {
                return List.of(row("status", "SUCCESS", "end_time", "r1", "total_output_rows", "7", "total_output_bytes", "70", "error", ""));
            }
            public List<Map<String, String>> lineage(String job, String runId) {
                return List.of(row("partition", "year=2020"));
            }
        };
        CatalogOverlay ov = new CatalogOverlay(p -> Optional.empty(),
                store(Set.of(), List.of(), List.of(), List.of()), stage2);

        MetadataNode x = new MetadataNode("xform:DAILY", NodeKind.TRANSFORMED_TABLE, "DAILY", Description.EMPTY, Map.of());
        OperationalOverlay o = ov.overlayFor(x);
        assertEquals("SUCCESS", o.latestStatus());
        assertEquals(7, o.totalOutputRows());
        assertEquals(1, o.lineageRefs().size());

        MetadataNode notHosted = new MetadataNode("xform:OTHER", NodeKind.TRANSFORMED_TABLE, "OTHER", Description.EMPTY, Map.of());
        assertEquals(OperationalOverlay.NONE, ov.overlayFor(notHosted));
    }

    @Test
    void semanticAndReferenceNodesHaveNoOverlay() {
        CatalogOverlay ov = new CatalogOverlay(p -> Optional.empty(),
                store(Set.of(), List.of(), List.of(), List.of()), null);
        for (NodeKind k : List.of(NodeKind.KPI, NodeKind.REPORT, NodeKind.REFERENCE_TABLE)) {
            MetadataNode n = new MetadataNode(IdScheme.token(k) + ":x", k, "x", Description.EMPTY, Map.of());
            assertEquals(OperationalOverlay.NONE, ov.overlayFor(n), "no overlay for " + k);
        }
    }

    @Test
    void integratesWithGraphServiceHydration(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        StatusStore ss = store(Set.of("b1"),
                List.of(row("status", "SUCCESS", "end_time", "t", "total_output_rows", "5", "total_output_bytes", "5", "error", "")),
                List.of(row("parsed_rows", "5", "error_rows", "0")),
                List.of(row("partition", "year=2020")));
        CatalogOverlay ov = new CatalogOverlay(p -> p.equals("mini_etl") ? Optional.of(cfg) : Optional.empty(), ss, null);

        ConfigSource cs = new ConfigSource() {
            public List<PipelineConfig> pipelines() { return List.of(cfg); }
            public List<EnrichmentConfig> enrichments() { return new ArrayList<>(); }
            public List<SemanticModel> semantics() { return new ArrayList<>(); }
        };
        MetadataGraphService svc = new MetadataGraphService(cs, ov);

        MetadataNode hydrated = svc.hydrated("event:mini_etl/mini");
        assertEquals("SUCCESS", hydrated.overlay().latestStatus());
        assertEquals(5, hydrated.overlay().totalOutputRows());
        // structural-only access carries no overlay
        assertNull(svc.node("event:mini_etl/mini").overlay());
    }
}
