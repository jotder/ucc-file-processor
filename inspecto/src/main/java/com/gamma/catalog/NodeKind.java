package com.gamma.catalog;

import com.gamma.api.PublicApi;

/**
 * The kind of a {@link MetadataNode} in the metadata graph.
 *
 * <p>Every node is derived from something the platform already defines or emits — a pipeline,
 * a schema, an emitted event table, a Stage-2 transform, a join reference, or a semantic
 * artifact (KPI / report) from a {@code *_meta.toon}. The graph links them so a consumer can
 * traverse from a KPI all the way down to the source columns that feed it.
 */
@PublicApi(since = "4.0.0")
public enum NodeKind {
    /** A configured pipeline's data-origin stream ({@code stream:<pipeline>}). */
    STREAM,
    /** A raw input schema ({@code schema:<pipeline>/<key|table>}); parent of {@link #COLUMN} nodes. */
    RAW_SCHEMA,
    /** A single described column ({@code col:<pipeline>/<KEY>/<COL>}). */
    COLUMN,
    /** A Stage-1 emitted, partitioned event table ({@code event:<pipeline>/<KEY|table>}). */
    TABLE,
    /** A Stage-2 enrichment output table ({@code xform:<enrichName>}). */
    DERIVED_TABLE,
    /** A reference/dimension table joined into a transform ({@code ref:<enrichName>/<refName>}). */
    REFERENCE_DATASET,
    /** A named KPI from a {@code *_meta.toon} ({@code kpi:<name>}). */
    KPI,
    /** A named report from a {@code *_meta.toon} ({@code report:<name>}). */
    REPORT
}
