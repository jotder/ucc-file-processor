import type { GeoData } from './geo-types';

/**
 * The **GeoSource seam** (GLOSSARY §11-Geo, plan: docs/superpower/geo-map-analysis-plan.md) — the
 * geo parallel of `inspecto/graph`'s GraphSource: one renderer (`MapViewComponent`) + one query
 * shape + N pluggable sources. Types only, framework-agnostic; concrete sources live with the
 * Geo Map studio feature.
 */

/**
 * The map planes a query can target: `dataset` (lat/lon point projection over a Dataset) and
 * `od-routes` (origin/destination pair projection → routes, Phase 2).
 */
export type GeoSourceId = 'dataset' | 'od-routes';

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

/**
 * The `od-routes` GeoSource mapping (Phase 2): each row is one origin→destination movement.
 * Distinct endpoints fold into points; rows fold into routes deduplicated per
 * (origin, destination, kind) with a summed weight.
 */
export interface RouteProjection {
    datasetId: string;
    fromLatCol: string;
    fromLonCol: string;
    toLatCol: string;
    toLonCol: string;
    /** Columns naming the endpoints (fall back to rounded coordinates). */
    fromCol?: string;
    toCol?: string;
    /** Column whose value kinds the route (drives colour). */
    kindCol?: string;
    /** Column carrying the event time (epoch millis or a parseable date string). */
    timeCol?: string;
}

/** A camera position captured with a saved Geo View. */
export interface GeoCamera {
    center: [number, number];
    zoom: number;
}

/** The unified GeoQuery — a source reads only the fields it understands. */
export interface GeoQuery {
    /** The `dataset` source's column mapping. */
    projection?: GeoProjection;
    /** The `od-routes` source's column mapping. */
    routes?: RouteProjection;
}

/** One pluggable origin of map data. `query()` may hit the backend or derive client-side. */
export interface GeoSource {
    readonly id: GeoSourceId;
    readonly label: string;
    query(q: GeoQuery): Promise<GeoData>;
}
