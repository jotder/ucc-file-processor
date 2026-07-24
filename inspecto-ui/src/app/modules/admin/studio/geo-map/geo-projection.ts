import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
    GeoData, GeoPoint, GeoProjection, GeoQuery, GeoRoute, GeoSource, RouteProjection, validCoordinate,
} from 'app/inspecto/geo';
import { GeoProjectionResult, GeoService } from 'app/inspecto/api';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { datasetRows } from 'app/modules/admin/studio/link-analysis/entity-projection';

/**
 * The `dataset` **GeoSource**: project a Dataset's rows onto the map — each row with a valid
 * WGS84 lat/lon becomes a {@link GeoPoint}. Since Phase 4 (`GeoRoutes`, 2026-07-22) this is
 * **backend-first**: `POST /geo/projection` does the DuckDB-side fold over the real Dataset
 * (scaling far beyond the browser's {@link GEO_POINT_CAP}) and {@link foldServerResult} maps the
 * result into the identical `GeoPoint` shape. When the backend is unavailable (offline demo — the
 * mock answers 501, or a pre-Phase-4 backend) it falls back to the original client-side fold over
 * the Dataset editor's sample rows, byte-identical to the mock-first behaviour. Mirrors the Link
 * Analysis studio's `EntityProjectionGraphSource`/`InvService` backend-first pattern.
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

/** Fold a server `/geo/projection`|`/geo/routes` result into the same `ProjectedGeo` shape as the client fold. */
function foldServerResult(res: GeoProjectionResult): ProjectedGeo {
    return {
        points: res.points.map((p) => ({
            id: p.id, lat: p.lat, lon: p.lon, kind: p.kind, label: p.label, time: p.time, attrs: p.attrs,
        })),
        routes: res.routes.map((r) => ({ id: r.id, from: r.from, to: r.to, kind: r.kind, label: r.kind, weight: r.weight })),
        truncated: res.truncated,
        skipped: res.skipped,
    };
}

/** The pluggable source: backend-first ({@code POST /geo/projection}); Dataset rows → {@link projectPoints} on failure. */
export class DatasetGeoSource implements GeoSource {
    readonly id = 'dataset' as const;
    readonly label = 'Locations (from a Dataset)';
    constructor(private datasets: DatasetsService, private geo: GeoService) {}

    async query(q: GeoQuery): Promise<ProjectedGeo> {
        const p = q.projection;
        if (!p?.datasetId) throw new Error('The dataset source needs a Dataset mapping.');
        if (!p.latCol || !p.lonCol) throw new Error('The mapping needs a latitude and a longitude column.');
        try {
            const res = await firstValueFrom(this.geo.project({
                dataset: p.datasetId,
                latCol: p.latCol,
                lonCol: p.lonCol,
                entityCol: p.entityCol || undefined,
                kindCol: p.kindCol || undefined,
                timeCol: p.timeCol || undefined,
            }));
            return foldServerResult(res);
        } catch {
            // Offline / mock (501) or an older backend: the original client-side sample fold.
            const ds = await firstValueFrom(this.datasets.get(p.datasetId));
            const out = projectPoints(datasetRows(ds), p);
            if (isGeoProjectionError(out)) throw new Error(out.error);
            return out;
        }
    }
}

/** The `od-routes` source: backend-first ({@code POST /geo/routes}); Dataset rows → {@link projectRoutes} on failure. */
export class RouteProjectionGeoSource implements GeoSource {
    readonly id = 'od-routes' as const;
    readonly label = 'Routes (origin → destination)';
    constructor(private datasets: DatasetsService, private geo: GeoService) {}

    async query(q: GeoQuery): Promise<ProjectedGeo> {
        const p = q.routes;
        if (!p?.datasetId) throw new Error('The routes source needs a Dataset mapping.');
        if (!p.fromLatCol || !p.fromLonCol || !p.toLatCol || !p.toLonCol) {
            throw new Error('The mapping needs origin and destination latitude/longitude columns.');
        }
        try {
            const res = await firstValueFrom(this.geo.routes({
                dataset: p.datasetId,
                fromLatCol: p.fromLatCol,
                fromLonCol: p.fromLonCol,
                toLatCol: p.toLatCol,
                toLonCol: p.toLonCol,
                fromCol: p.fromCol || undefined,
                toCol: p.toCol || undefined,
                kindCol: p.kindCol || undefined,
            }));
            return foldServerResult(res);
        } catch {
            // Offline / mock (501) or an older backend: the original client-side sample fold.
            const ds = await firstValueFrom(this.datasets.get(p.datasetId));
            const out = projectRoutes(datasetRows(ds), p);
            if (isGeoProjectionError(out)) throw new Error(out.error);
            return out;
        }
    }
}

/** Root factory holding one instance per source, in stable order (mirrors GraphSourcesService). */
@Injectable({ providedIn: 'root' })
export class GeoSourcesService {
    readonly sources: GeoSource[] = [
        new DatasetGeoSource(inject(DatasetsService), inject(GeoService)),
        new RouteProjectionGeoSource(inject(DatasetsService), inject(GeoService)),
    ];
}
