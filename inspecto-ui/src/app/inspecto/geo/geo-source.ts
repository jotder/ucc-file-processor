import type { GeoData } from './geo-types';

/**
 * The **GeoSource seam** (GLOSSARY §11-Geo, plan: docs/superpower/geo-map-analysis-plan.md) — the
 * geo parallel of `inspecto/graph`'s GraphSource: one renderer (`MapViewComponent`) + one query
 * shape + N pluggable sources. Types only, framework-agnostic; concrete sources live with the
 * Geo Map studio feature.
 */

/** The map planes a query can target. MVP: `dataset` (lat/lon projection over a Dataset). */
export type GeoSourceId = 'dataset';

/**
 * The `dataset` GeoSource mapping: which Dataset columns carry the coordinates and, optionally,
 * the entity identity/kind/time. Rows with invalid coordinates are skipped (and counted).
 */
export interface GeoProjection {
    datasetId: string;
    latCol: string;
    lonCol: string;
    /** Column whose value labels the point (the located entity). */
    entityCol?: string;
    /** Column whose value kinds the point (drives icon/colour). */
    kindCol?: string;
    /** Column carrying the event time (epoch millis or a parseable date string). */
    timeCol?: string;
}

/** The unified GeoQuery — a source reads only the fields it understands. */
export interface GeoQuery {
    /** The `dataset` source's column mapping. */
    projection?: GeoProjection;
}

/** One pluggable origin of map data. `query()` may hit the backend or derive client-side. */
export interface GeoSource {
    readonly id: GeoSourceId;
    readonly label: string;
    query(q: GeoQuery): Promise<GeoData>;
}
