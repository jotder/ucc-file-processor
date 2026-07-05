import { describe, expect, it } from 'vitest';
import { GEO_POINT_CAP, isGeoProjectionError, projectPoints } from './geo-projection';

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

    it('truncates at the point cap and says so', () => {
        const many = Array.from({ length: GEO_POINT_CAP + 10 }, (_, i) => ({ lat: 10 + (i % 50) * 0.01, lon: 20, site: `s${i}` }));
        const out = projectPoints(many, { datasetId: 'x', latCol: 'lat', lonCol: 'lon' });
        if (isGeoProjectionError(out)) throw new Error(out.error);
        expect(out.points).toHaveLength(GEO_POINT_CAP);
        expect(out.truncated).toBe(true);
    });
});
