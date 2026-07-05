import { validCoordinate } from './geo-analysis';

/**
 * The **geocoding seam** (geo plan Phase 4, decision D4: demoted from MVP, returns as a pluggable seam
 * with an offline lookup-table default). A `Geocoder` turns a place name into candidate coordinates so an
 * investigator can jump the map to "Dhaka" without knowing lat/lon. Framework-free and pure like the rest
 * of `inspecto/geo/`; the Angular `GeocoderService` picks an implementation. A future online geocoder
 * (Nominatim URL) implements the same interface — one seam, swappable backend.
 */

/** One geocoding candidate. */
export interface GeocodeResult {
    name: string;
    lat: number;
    lon: number;
    /** A disambiguating label (country / admin region), when the source has one. */
    context?: string;
}

/** A named place in an offline lookup table. */
export interface PlaceRecord {
    name: string;
    lat: number;
    lon: number;
    country?: string;
}

export interface Geocoder {
    readonly id: string;
    readonly label: string;
    geocode(query: string): Promise<GeocodeResult[]>;
}

/**
 * Pure prefix/substring match over a place table, best-first: exact name, then prefix, then substring
 * (all case-insensitive), capped. No fuzzy matching — deliberate MVP scope, mirroring the graph/geo
 * analysis libs' "simple beats clever" rule.
 */
export function offlineGeocode(query: string, table: PlaceRecord[], limit = 8): GeocodeResult[] {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    const scored: { r: PlaceRecord; score: number }[] = [];
    for (const r of table) {
        const name = r.name.toLowerCase();
        const score = name === q ? 0 : name.startsWith(q) ? 1 : name.includes(q) ? 2 : -1;
        if (score >= 0 && validCoordinate(r.lat, r.lon)) scored.push({ r, score });
    }
    return scored
        .sort((a, b) => a.score - b.score || a.r.name.localeCompare(b.r.name))
        .slice(0, limit)
        .map(({ r }) => ({ name: r.name, lat: r.lat, lon: r.lon, context: r.country }));
}

/** The offline geocoder — matches against a bundled {@link PlaceRecord} table (no network). */
export class OfflineGeocoder implements Geocoder {
    readonly id = 'offline' as const;
    readonly label = 'Offline place table';
    constructor(private table: PlaceRecord[]) {}

    geocode(query: string): Promise<GeocodeResult[]> {
        return Promise.resolve(offlineGeocode(query, this.table));
    }
}
