package com.gamma.catalog;

import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;

import java.util.List;

/**
 * Supplies the configuration the metadata graph is assembled from. This is the seam that lets
 * {@link MetadataGraphService} stay independent of <em>how</em> configs are obtained.
 *
 * <p>Today {@code CollectorService} implements this by reloading its configured paths; when the M1
 * {@code ConfigRegistry} (O(1), watch/reload) lands it will implement the same interface and the
 * service's constructor argument swaps with no change to {@code MetadataGraphService}.
 */
public interface ConfigSource {

    /** All Stage-1 pipelines currently configured. */
    List<PipelineConfig> pipelines();

    /** All Stage-2 enrichment jobs currently configured. */
    List<EnrichmentConfig> enrichments();

    /** All loaded {@code *_meta.toon} semantic models (KPI catalog + domain notes). */
    List<SemanticModel> semantics();
}
