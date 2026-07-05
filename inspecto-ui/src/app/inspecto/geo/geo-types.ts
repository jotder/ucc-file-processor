/**
 * Shared geo data shapes (Geo Map Analysis) — the map-renderer contract, the geo analog of
 * `inspecto/graph`'s `G6GraphData`. Pure types, no Angular/MapLibre imports.
 * Design: docs/superpower/geo-map-analysis-plan.md.
 */

/** A located entity occurrence: one marker on the map. Coordinates are WGS84 degrees. */
export interface GeoPoint {
    id: string;
    lat: number;
    lon: number;
    /** Entity kind — drives icon/colour, mirrors graph node `kind`. */
    kind: string;
    label?: string;
    /** Event time (epoch millis) when the source maps a time field. */
    time?: number;
    /** Source-row attributes surfaced in the detail sheet. */
    attrs?: Record<string, unknown>;
}

/** A relationship between two located points, drawn as a route line. */
export interface GeoRoute {
    id: string;
    /** Endpoint point ids. */
    from: string;
    to: string;
    kind: string;
    label?: string;
    weight?: number;
    time?: number;
}

/** What a GeoSource query returns and the map view renders. */
export interface GeoData {
    points: GeoPoint[];
    routes: GeoRoute[];
    /** Set when a projection cap truncated the result (banner in the studio). */
    truncated?: boolean;
}
