package com.gamma.catalog;

/**
 * The kind of a directed {@link MetadataEdge} in the metadata graph.
 *
 * <p>All edges are derived from configuration or the {@code *_meta.toon} semantic layer — never
 * from runtime audit — so the structural graph is deterministic and cacheable. Operational state
 * lives on the {@link MetadataNode#overlay()}, not on edges.
 */
public enum EdgeKind {
    /** SOURCE → TABLE: the source emits this partitioned event table. */
    EMITS,
    /** SOURCE → RAW_SCHEMA: the source declares this input schema. */
    DECLARES,
    /** RAW_SCHEMA → COLUMN: the schema describes this column. */
    DESCRIBES,
    /** RAW_SCHEMA → TABLE: the raw schema is partitioned/materialized into this event table. */
    MATERIALIZES,
    /** TABLE → DERIVED_TABLE (or DERIVED → DERIVED): data lineage / feeds. */
    FEEDS,
    /** REFERENCE_DATASET → DERIVED_TABLE: the reference is joined into the transform. */
    JOINS_INTO,
    /** KPI → table/column: the KPI is computed from this input. */
    COMPUTED_FROM,
    /** REPORT → KPI/table: the report consumes this artifact. */
    CONSUMES
}
