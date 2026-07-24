import { describe, expect, it } from 'vitest';
import { of, throwError } from 'rxjs';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import {
    DatasetGeoSource, GEO_POINT_CAP, ProjectedGeo, RouteProjectionGeoSource,
    isGeoProjectionError, projectPoints, projectRoutes,
} from './geo-projection';

const ROWS = [
    { site: 'T1', lat: 23.81, lon: 90.41, type: 'tower', seen: '2026-06-01T10:00:00Z' },
    { site: 'T2', lat: 23.79, lon: 90.4, type: 'tower', seen: '2026-06-01T11:00:00Z' },
    { site: 'D1', lat: 51.5, lon: -0.12, type: 'device', seen: 'not-a-date' },
    { site: 'BAD', lat: 999, lon: 0, type: 'device', seen: '' }, // invalid latitude
    { site: 'NULL', lat: null, lon: 90, type: 'device', seen: '' }, // missing coordinate
];

describe('projectPoints', () => {
    it('rejects a mapping without coordinates or with unknown columns', () => {
        expect(projectPoints(ROWS, { datasetId: 'x', latCol: '', lonCol: 'lon' })).toEqual({
            error: 'The mapping needs a latitude and a longitude column.',
        });
        const out = projectPoints(ROWS, { datasetId: 'x', latCol: 'nope', lonCol: 'lon' });
        expect(isGeoProjectionError(out) && out.error).toMatch(/'nope' is not in the dataset/);
    });

    it('folds valid rows into points and counts the skipped invalid ones', () => {
        const out = projectPoints(ROWS, {
            datasetId: 'x', latCol: 'lat', lonCol: 'lon', entityCol: 'site', kindCol: 'type', timeCol: 'seen',
        });
        if (isGeoProjectionError(out)) throw new Error(out.error);
        expect(out.points.map((p) => p.label)).toEqual(['T1', 'T2', 'D1']);
        expect(out.skipped).toBe(2);
        expect(out.truncated).toBe(false);
        expect(out.points[0].kind).toBe('tower');
        expect(out.points[0].time).toBe(Date.parse('2026-06-01T10:00:00Z'));
        expect(out.points[2].time).toBeUndefined(); // unparseable date
        expect(out.points[0].attrs).toBe(ROWS[0]); // full row for the detail sheet
    });

    it('defaults kind and label when unmapped', () => {
        const out = projectPoints(ROWS.slice(0, 1), { datasetId: 'x', latCol: 'lat', lonCol: 'lon' });
        if (isGeoProjectionError(out)) throw new Error(out.error);
        expect(out.points[0].kind).toBe('point');
        expect(out.points[0].label).toBeUndefined();
    });

    it('folds O/D rows into endpoint points + weighted routes, skipping broken legs', () => {
        const legs = [
            { fa: 23.81, fo: 90.41, ta: 25.2, to: 55.27, from: 'Dhaka', to_c: 'Dubai', ch: 'hundi', at: '2026-06-02T09:30:00Z' },
            { fa: 23.81, fo: 90.41, ta: 25.2, to: 55.27, from: 'Dhaka', to_c: 'Dubai', ch: 'hundi', at: '2026-06-02T11:30:00Z' },
            { fa: 25.2, fo: 55.27, ta: 51.5, to: -0.13, from: 'Dubai', to_c: 'London', ch: 'wire', at: '2026-06-02T12:30:00Z' },
            { fa: 23.81, fo: 90.41, ta: null, to: null, from: 'Dhaka', to_c: 'Nowhere', ch: 'wire', at: '' },
        ];
        const out = projectRoutes(legs as unknown as Record<string, unknown>[], {
            datasetId: 'x', fromLatCol: 'fa', fromLonCol: 'fo', toLatCol: 'ta', toLonCol: 'to',
            fromCol: 'from', toCol: 'to_c', kindCol: 'ch', timeCol: 'at',
        });
        if (isGeoProjectionError(out)) throw new Error(out.error);
        expect(out.points.map((p) => p.label).sort()).toEqual(['Dhaka', 'Dubai', 'London']);
        expect(out.routes).toHaveLength(2);
        const dhkDxb = out.routes.find((r) => r.kind === 'hundi')!;
        expect(dhkDxb.weight).toBe(2); // folded repeat leg
        expect(dhkDxb.label).toBe('hundi · 2');
        expect(out.skipped).toBe(1);
    });

    it('rejects a route mapping without both coordinate pairs', () => {
        const out = projectRoutes([], { datasetId: 'x', fromLatCol: 'a', fromLonCol: 'b', toLatCol: '', toLonCol: 'd' });
        expect(isGeoProjectionError(out) && out.error).toMatch(/origin and destination/);
    });

    it('truncates at the point cap and says so', () => {
        const many = Array.from({ length: GEO_POINT_CAP + 10 }, (_, i) => ({ lat: 10 + (i % 50) * 0.01, lon: 20, site: `s${i}` }));
        const out = projectPoints(many, { datasetId: 'x', latCol: 'lat', lonCol: 'lon' });
        if (isGeoProjectionError(out)) throw new Error(out.error);
        expect(out.points).toHaveLength(GEO_POINT_CAP);
        expect(out.truncated).toBe(true);
    });
});

// Backend-first wiring (Phase 4, `GeoRoutes`): mirrors the Link Analysis studio's
// `EntityProjectionGraphSource`/`InvService` pattern — the real assertions live here since
// `geo-projection.ts` is the only place `GeoService` is called.
describe('DatasetGeoSource / RouteProjectionGeoSource (backend-first, client fallback)', () => {
    const ds: Dataset = {
        id: 'geo-ds', name: 'Geo', kind: 'physical', sourceName: 'simbox_sweep',
        query: null, physicalRef: null, columns: [], measures: [], calculated: [],
    };

    it('DatasetGeoSource calls POST /geo/projection first and folds the server result', async () => {
        const geo = {
            project: (req: unknown) => {
                expect(req).toEqual({
                    dataset: 'geo-ds', latCol: 'lat', lonCol: 'lon',
                    entityCol: 'msisdn', kindCol: 'role', timeCol: undefined,
                });
                return of({
                    points: [{ id: 'pt:0', lat: 1, lon: 2, kind: 'tower', label: 'T1' }],
                    routes: [],
                    truncated: false,
                    skipped: 3,
                });
            },
        } as never;
        const datasets = { get: () => { throw new Error('must not fetch rows on the backend path'); } } as never;
        const src = new DatasetGeoSource(datasets, geo);
        const out = (await src.query({
            projection: { datasetId: 'geo-ds', latCol: 'lat', lonCol: 'lon', entityCol: 'msisdn', kindCol: 'role' },
        })) as ProjectedGeo;
        expect(out.points).toEqual([{ id: 'pt:0', lat: 1, lon: 2, kind: 'tower', label: 'T1', time: undefined, attrs: undefined }]);
        expect(out.skipped).toBe(3);
    });

    it('DatasetGeoSource falls back to the client fold over sample rows when the backend is unavailable', async () => {
        const geo = { project: () => throwError(() => new Error('offline')) } as never;
        const src = new DatasetGeoSource({ get: () => of(ds) } as never, geo);
        const out = (await src.query({
            projection: { datasetId: 'geo-ds', latCol: 'lat', lonCol: 'lon', entityCol: 'msisdn' },
        })) as ProjectedGeo;
        expect(out.points.length).toBeGreaterThan(0);
    });

    it('RouteProjectionGeoSource calls POST /geo/routes first and folds the server result', async () => {
        const geo = {
            routes: (req: unknown) => {
                expect(req).toEqual({
                    dataset: 'geo-ds', fromLatCol: 'fa', fromLonCol: 'fo', toLatCol: 'ta', toLonCol: 'to',
                    fromCol: undefined, toCol: undefined, kindCol: undefined,
                });
                return of({
                    points: [{ id: 'ep:A', lat: 1, lon: 2, kind: 'place', label: 'A' }],
                    routes: [{ id: 'ep:A->ep:B:route', from: 'ep:A', to: 'ep:B', kind: 'route', weight: 4 }],
                    truncated: false,
                    skipped: 0,
                });
            },
        } as never;
        const datasets = { get: () => { throw new Error('must not fetch rows on the backend path'); } } as never;
        const src = new RouteProjectionGeoSource(datasets, geo);
        const out = (await src.query({
            routes: { datasetId: 'geo-ds', fromLatCol: 'fa', fromLonCol: 'fo', toLatCol: 'ta', toLonCol: 'to' },
        })) as ProjectedGeo;
        expect(out.routes).toEqual([{ id: 'ep:A->ep:B:route', from: 'ep:A', to: 'ep:B', kind: 'route', label: 'route', weight: 4 }]);
    });

    it('RouteProjectionGeoSource falls back to the client fold when the backend is unavailable', async () => {
        const geo = { routes: () => throwError(() => new Error('offline')) } as never;
        const src = new RouteProjectionGeoSource({ get: () => of(ds) } as never, geo);
        await expect(src.query({
            routes: { datasetId: 'geo-ds', fromLatCol: 'a', fromLonCol: 'b', toLatCol: 'c', toLonCol: 'd' },
        })).rejects.toThrow(); // simbox_sweep has no o/d columns named a/b/c/d — surfaces as a mapping error
    });
});
