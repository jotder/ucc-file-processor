import { describe, expect, it } from 'vitest';
import {
    boundsOf,
    clusterLocations,
    filterByKinds,
    filterByTime,
    formatDistance,
    gridDensity,
    haversineMeters,
    nearby,
    searchPoints,
    validCoordinate,
    withinBBox,
} from './geo-analysis';
import { GeoData, GeoPoint } from './geo-types';

const pt = (id: string, lat: number, lon: number, kind = 'device', time?: number): GeoPoint => ({
    id, lat, lon, kind, label: id.toUpperCase(), time,
});

const DATA: GeoData = {
    points: [
        pt('dhk', 23.8103, 90.4125, 'tower', 100),
        pt('dhk2', 23.815, 90.42, 'device', 200),
        pt('lon', 51.5074, -0.1278, 'device', 300),
        pt('nyc', 40.7128, -74.006, 'device'),
    ],
    routes: [
        { id: 'r1', from: 'dhk', to: 'lon', kind: 'call', time: 150 },
        { id: 'r2', from: 'lon', to: 'nyc', kind: 'call' },
    ],
};

describe('geo-analysis', () => {
    it('validates WGS84 coordinates', () => {
        expect(validCoordinate(23.8, 90.4)).toBe(true);
        expect(validCoordinate(-90, 180)).toBe(true);
        expect(validCoordinate(91, 0)).toBe(false);
        expect(validCoordinate(0, -181)).toBe(false);
        expect(validCoordinate(NaN, 0)).toBe(false);
        expect(validCoordinate('23.8', 90)).toBe(false);
    });

    it('computes haversine distances (Dhaka→London ≈ 8,015 km)', () => {
        const d = haversineMeters(23.8103, 90.4125, 51.5074, -0.1278);
        expect(d).toBeGreaterThan(7_900_000);
        expect(d).toBeLessThan(8_100_000);
        expect(haversineMeters(10, 10, 10, 10)).toBe(0);
    });

    it('formats distances human-readably', () => {
        expect(formatDistance(847.3)).toBe('847 m');
        expect(formatDistance(12_400)).toBe('12.4 km');
    });

    it('computes bounds and bbox membership', () => {
        expect(boundsOf([])).toBeNull();
        const b = boundsOf(DATA.points)!;
        expect(b).toEqual([-74.006, 23.8103, 90.42, 51.5074]);
        const dhakaOnly = withinBBox(DATA.points, [90, 23, 91, 24]);
        expect(dhakaOnly.map((p) => p.id)).toEqual(['dhk', 'dhk2']);
    });

    it('finds nearby points sorted by distance', () => {
        const hits = nearby(DATA.points, 23.81, 90.41, 5000);
        expect(hits.map((h) => h.point.id)).toEqual(['dhk', 'dhk2']);
        expect(hits[0].distanceM).toBeLessThan(hits[1].distanceM);
    });

    it('searches label, id and kind case-insensitively', () => {
        expect(searchPoints(DATA, 'DHK')).toEqual(['dhk', 'dhk2']);
        expect(searchPoints(DATA, 'tower')).toEqual(['dhk']);
        expect(searchPoints(DATA, '')).toEqual([]);
    });

    it('filters by kind, dropping routes with a lost endpoint', () => {
        const towers = filterByKinds(DATA, ['tower']);
        expect(towers.points.map((p) => p.id)).toEqual(['dhk']);
        expect(towers.routes).toEqual([]);
        expect(filterByKinds(DATA, null)).toBe(DATA);
    });

    it('filters by time, keeping untimed points', () => {
        const early = filterByTime(DATA, null, 250);
        expect(early.points.map((p) => p.id)).toEqual(['dhk', 'dhk2', 'nyc']);
        expect(early.routes.map((r) => r.id)).toEqual([]);
        const all = filterByTime(DATA, null, null);
        expect(all.points).toHaveLength(4);
        expect(all.routes).toHaveLength(2);
    });

    it('bins density on a grid, densest first', () => {
        const cells = gridDensity(DATA.points, 1);
        expect(cells[0].count).toBe(2);
        expect(cells[0].pointIds).toEqual(['dhk', 'dhk2']);
        expect(cells).toHaveLength(3);
    });

    it('clusters co-located points, excluding singletons', () => {
        const clusters = clusterLocations(DATA.points, 2000);
        expect(clusters).toHaveLength(1);
        expect(clusters[0].sort()).toEqual(['dhk', 'dhk2']);
    });
});
