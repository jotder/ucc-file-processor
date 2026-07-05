import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
    GeoData, GeoPoint, GeoProjection, GeoQuery, GeoRoute, GeoSource, RouteProjection, validCoordinate,
} from 'app/inspecto/geo';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { datasetRows } from 'app/modules/admin/studio/link-analysis/entity-projection';

/**
 * The `dataset` **GeoSource**: project a Dataset's rows onto the map — each row with a valid
 * WGS84 lat/lon becomes a {@link GeoPoint}. Client-side over the same offline row seam the
 * Dataset editor and the entity-projection use (`datasetRows`); the real backend projection is
 * Phase 4 (docs/superpower/geo-map-analysis-plan.md).
 */

/** Above this many points the projection truncates (and says so) rather than melt the map. */
export const GEO_POINT_CAP = 5000;

/** A projection failure the UI can render inline (bad mapping ≠ a thrown stack). */
export interface GeoProjectionError {
    error: string;
}

export interface ProjectedGeo extends GeoData {
    truncated: boolean;
    /** Rows skipped for a missing/invalid coordinate — surfaced as a banner when > 0. */
    skipped: number;
}

export function isGeoProjectionError(v: ProjectedGeo | GeoProjectionError): v is GeoProjectionError {
    return 'error' in v;
}

/** Pure rows→points fold. Rows with an invalid or missing coordinate are skipped and counted. */
export function projectPoints(rows: Record<string, unknown>[], p: GeoProjection): ProjectedGeo | GeoProjectionError {
    if (!p.latCol || !p.lonCol) return { error: 'The mapping needs a latitude and a longitude column.' };
    for (const col of [p.latCol, p.lonCol, p.entityCol, p.kindCol, p.timeCol]) {
        if (col && rows.length && !(col in rows[0])) return { error: `Column '${col}' is not in the dataset.` };
    }

    const points: GeoPoint[] = [];
    let truncated = false;
    let skipped = 0;
    // Empty/null must stay NaN — Number(null) is 0, which would silently drop the row on "null island".
    const coord = (v: unknown): number => (v === null || v === undefined || v === '' ? NaN : Number(v));
    for (let i = 0; i < rows.length; i++) {
        const row = rows[i];
        const lat = coord(row[p.latCol]);
        const lon = coord(row[p.lonCol]);
        if (!validCoordinate(lat, lon)) {
            skipped++;
            continue;
        }
        if (points.length >= GEO_POINT_CAP) {
            truncated = true;
            break;
        }
        const label = p.entityCol ? String(row[p.entityCol] ?? '').trim() : '';
        const time = p.timeCol ? parseTime(row[p.timeCol]) : undefined;
        points.push({
            id: `pt:${i}`,
            lat,
            lon,
            kind: (p.kindCol ? String(row[p.kindCol] ?? '').trim() : '') || 'point',
            label: label || undefined,
            time,
            attrs: row,
        });
    }
    return { points, routes: [], truncated, skipped };
}

/** Epoch millis from a number or a parseable date string; `undefined` when unparseable. */
function parseTime(v: unknown): number | undefined {
    if (typeof v === 'number' && Number.isFinite(v)) return v;
    if (typeof v === 'string') {
        const t = Date.parse(v);
        return Number.isNaN(t) ? undefined : t;
    }
    return undefined;
}

/**
 * Pure rows→routes fold (Phase 2): each row is one origin→destination movement. Endpoints fold
 * into named points (by `fromCol`/`toCol`, else rounded coordinates); rows fold into routes
 * deduplicated per (origin, destination, kind) with a summed `weight`. Rows with an invalid
 * coordinate on either end are skipped and counted.
 */
export function projectRoutes(rows: Record<string, unknown>[], p: RouteProjection): ProjectedGeo | GeoProjectionError {
    if (!p.fromLatCol || !p.fromLonCol || !p.toLatCol || !p.toLonCol) {
        return { error: 'The mapping needs origin and destination latitude/longitude columns.' };
    }
    for (const col of [p.fromLatCol, p.fromLonCol, p.toLatCol, p.toLonCol, p.fromCol, p.toCol, p.kindCol, p.timeCol]) {
        if (col && rows.length && !(col in rows[0])) return { error: `Column '${col}' is not in the dataset.` };
    }

    const coord = (v: unknown): number => (v === null || v === undefined || v === '' ? NaN : Number(v));
    const points = new Map<string, GeoPoint>();
    const routes = new Map<string, GeoRoute>();
    let truncated = false;
    let skipped = 0;

    const endpoint = (lat: number, lon: number, name: string, row: Record<string, unknown>): string | null => {
        const label = name || `${lat.toFixed(4)}, ${lon.toFixed(4)}`;
        const id = `ep:${label}`;
        if (!points.has(id)) {
            if (points.size >= GEO_POINT_CAP) {
                truncated = true;
                return null;
            }
            points.set(id, { id, lat, lon, kind: 'place', label, attrs: row });
        }
        return id;
    };

    for (const row of rows) {
        const aLat = coord(row[p.fromLatCol]), aLon = coord(row[p.fromLonCol]);
        const bLat = coord(row[p.toLatCol]), bLon = coord(row[p.toLonCol]);
        if (!validCoordinate(aLat, aLon) || !validCoordinate(bLat, bLon)) {
            skipped++;
            continue;
        }
        const from = endpoint(aLat, aLon, p.fromCol ? String(row[p.fromCol] ?? '').trim() : '', row);
        const to = endpoint(bLat, bLon, p.toCol ? String(row[p.toCol] ?? '').trim() : '', row);
        if (!from || !to) continue;
        const kind = (p.kindCol ? String(row[p.kindCol] ?? '').trim() : '') || 'route';
        const time = p.timeCol ? parseTime(row[p.timeCol]) : undefined;
        const key = `${from}->${to}:${kind}`;
        const existing = routes.get(key);
        if (existing) {
            existing.weight = (existing.weight ?? 1) + 1;
            existing.label = `${kind} · ${existing.weight}`;
        } else {
            routes.set(key, { id: key, from, to, kind, label: kind, weight: 1, time });
        }
    }
    return { points: [...points.values()], routes: [...routes.values()], truncated, skipped };
}

/** The pluggable source: Dataset (by `projection.datasetId`) → rows → {@link projectPoints}. */
export class DatasetGeoSource implements GeoSource {
    readonly id = 'dataset' as const;
    readonly label = 'Locations (from a Dataset)';
    constructor(private datasets: DatasetsService) {}

    async query(q: GeoQuery): Promise<ProjectedGeo> {
        if (!q.projection?.datasetId) throw new Error('The dataset source needs a Dataset mapping.');
        const ds = await firstValueFrom(this.datasets.get(q.projection.datasetId));
        const out = projectPoints(datasetRows(ds), q.projection);
        if (isGeoProjectionError(out)) throw new Error(out.error);
        return out;
    }
}

/** The `od-routes` source: Dataset (by `routes.datasetId`) → rows → {@link projectRoutes}. */
export class RouteProjectionGeoSource implements GeoSource {
    readonly id = 'od-routes' as const;
    readonly label = 'Routes (origin → destination)';
    constructor(private datasets: DatasetsService) {}

    async query(q: GeoQuery): Promise<ProjectedGeo> {
        if (!q.routes?.datasetId) throw new Error('The routes source needs a Dataset mapping.');
        const ds = await firstValueFrom(this.datasets.get(q.routes.datasetId));
        const out = projectRoutes(datasetRows(ds), q.routes);
        if (isGeoProjectionError(out)) throw new Error(out.error);
        return out;
    }
}

/** Root factory holding one instance per source, in stable order (mirrors GraphSourcesService). */
@Injectable({ providedIn: 'root' })
export class GeoSourcesService {
    readonly sources: GeoSource[] = [
        new DatasetGeoSource(inject(DatasetsService)),
        new RouteProjectionGeoSource(inject(DatasetsService)),
    ];
}
