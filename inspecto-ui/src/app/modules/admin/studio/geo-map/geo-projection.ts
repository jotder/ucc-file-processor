import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { GeoData, GeoPoint, GeoProjection, GeoQuery, GeoSource, validCoordinate } from 'app/inspecto/geo';
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

/** Root factory holding one instance per source, in stable order (mirrors GraphSourcesService). */
@Injectable({ providedIn: 'root' })
export class GeoSourcesService {
    readonly sources: GeoSource[] = [new DatasetGeoSource(inject(DatasetsService))];
}
