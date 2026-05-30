package com.gamma.catalog;

import com.gamma.catalog.spi.DescriptionProvider;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link DescriptionProvider} SPI applied by {@link MetadataGraphService} (P5):
 * empty column descriptions get filled, authored ({@code MANUAL}) prose is never overwritten,
 * and the lean-core default (no-op via ServiceLoader) changes nothing.
 */
class DescriptionProviderTest {

    /** A schema where ID is authored and the other columns are blank (4-column header). */
    private static final String SCHEMA_WITH_DESC = """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type,description}:
                ID,"0",VARCHAR,"Primary identifier"
                AMT,"1",DOUBLE,""
                EVENT_DATE,"2",DATE,""
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """;

    /** Names every column "auto: <name>" with AI provenance. */
    private static final DescriptionProvider AUTO = new DescriptionProvider() {
        public String name() { return "auto"; }
        public Description describeColumn(ColumnContext ctx) {
            return new Description("auto: " + ctx.columnName(), Provenance.AI);
        }
    };

    private PipelineConfig load(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        Files.writeString(dir.resolve("mini_schema.toon"), SCHEMA_WITH_DESC, StandardCharsets.UTF_8);
        return PipelineConfig.load(pipe.toString());
    }

    private ConfigSource oneSource(PipelineConfig cfg) {
        return new ConfigSource() {
            public List<PipelineConfig> pipelines() { return List.of(cfg); }
            public List<EnrichmentConfig> enrichments() { return List.of(); }
            public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    @Test
    void providerFillsEmptyButNeverOverwritesAuthored(@TempDir Path dir) throws Exception {
        MetadataGraphService svc = new MetadataGraphService(oneSource(load(dir)), null, List.of(AUTO));

        MetadataNode id = svc.node("col:mini_etl/mini/ID");
        assertEquals("Primary identifier", id.description().text(), "authored prose preserved");
        assertEquals(Provenance.MANUAL, id.description().provenance());

        MetadataNode amt = svc.node("col:mini_etl/mini/AMT");
        assertEquals("auto: AMT", amt.description().text(), "empty column filled by provider");
        assertEquals(Provenance.AI, amt.description().provenance());
    }

    @Test
    void coreDefaultProviderLeavesEmptyColumnsEmpty(@TempDir Path dir) throws Exception {
        // The two-arg constructor discovers providers via ServiceLoader; core ships only the no-op.
        MetadataGraphService svc = new MetadataGraphService(oneSource(load(dir)), null);
        MetadataNode amt = svc.node("col:mini_etl/mini/AMT");
        assertFalse(amt.description().isPresent(), "no-op provider does not fabricate descriptions");
        assertEquals("Primary identifier", svc.node("col:mini_etl/mini/ID").description().text());
    }
}
