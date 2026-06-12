package com.gamma.agent.catalog;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.Description;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.Provenance;
import com.gamma.catalog.spi.DescriptionProvider.ColumnContext;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.catalog.SemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 tests for the AI {@link com.gamma.catalog.spi.DescriptionProvider}, CPU-only (a
 * {@link FakeModelProvider}, no Ollama). They prove the provider fills blank descriptions with
 * {@link Provenance#AI}, defers to authored {@code MANUAL} prose, and is abstain-safe + never
 * throwing when the model is down.
 */
class AiDescriptionProviderTest {

    private static ColumnContext blankCol() {
        return new ColumnContext("mini_etl", "mini", "AMT", "DOUBLE", "");
    }

    @Test
    void fillsBlankColumnWithAiProvenance() {
        AiDescriptionProvider p = new AiDescriptionProvider(FakeModelProvider.canned("The transaction amount."));
        Description d = p.describeColumn(blankCol());
        assertEquals("The transaction amount.", d.text());
        assertEquals(Provenance.AI, d.provenance());
    }

    @Test
    void abstainsWhenColumnAlreadyDescribed() {
        AiDescriptionProvider p = new AiDescriptionProvider(FakeModelProvider.canned("ignored"));
        Description d = p.describeColumn(new ColumnContext("mini_etl", "mini", "ID", "VARCHAR", "Primary identifier"));
        assertSame(Description.EMPTY, d, "never overwrite authored prose");
    }

    @Test
    void abstainsWhenModelUnavailableWithNoNetwork() {
        AiDescriptionProvider p = new AiDescriptionProvider(FakeModelProvider.down());
        assertSame(Description.EMPTY, p.describeColumn(blankCol()));
    }

    @Test
    void neverThrowsEvenWhenModelErrors() {
        // An "available" provider whose generate() blows up — the describer must swallow it.
        FakeModelProvider boom = FakeModelProvider.responding(r -> { throw new RuntimeException("boom"); });
        AiDescriptionProvider p = new AiDescriptionProvider(boom);
        assertSame(Description.EMPTY, assertDoesNotThrow(() -> p.describeColumn(blankCol())));
    }

    @Test
    void cleansModelOutputToOneTidyLine() {
        AiDescriptionProvider p = new AiDescriptionProvider(
                FakeModelProvider.canned("\"The amount of the transaction.\"\nExtra chatter here."));
        Description d = p.describeColumn(blankCol());
        assertEquals("The amount of the transaction.", d.text(), "first line, quotes stripped");
    }

    @Test
    void fillsCatalogBlanksButPreservesAuthoredViaMerge(@TempDir Path dir) throws Exception {
        // ID is authored (MANUAL) in the schema; AMT/EVENT_DATE are blank (NONE).
        Path pipe = AgentTestConfigs.writePipelineWithDescribedSchema(dir);
        PipelineConfig cfg = PipelineConfig.load(pipe.toString());
        ConfigSource cs = new ConfigSource() {
            public List<PipelineConfig> pipelines() { return List.of(cfg); }
            public List<EnrichmentConfig> enrichments() { return List.of(); }
            public List<SemanticModel> semantics() { return List.of(); }
        };

        // Inject the AI describer explicitly (3-arg ctor bypasses ServiceLoader) with a fake model.
        MetadataGraphService catalog = new MetadataGraphService(
                cs, null, List.of(new AiDescriptionProvider(FakeModelProvider.canned("auto description"))));

        MetadataNode amt = catalog.node("col:mini_etl/mini/AMT");
        assertEquals("auto description", amt.description().text(), "blank column filled by AI");
        assertEquals(Provenance.AI, amt.description().provenance());

        MetadataNode id = catalog.node("col:mini_etl/mini/ID");
        assertEquals("Primary identifier", id.description().text(), "authored prose preserved");
        assertEquals(Provenance.MANUAL, id.description().provenance());
    }
}
