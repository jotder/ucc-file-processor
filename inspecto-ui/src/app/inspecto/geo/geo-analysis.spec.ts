import { describe, expect, it } from 'vitest';
import {
    boundsOf,
    circleRing,
    clusterLocations,
    coLocationGraph,
    coLocations,
    filterByKinds,
    filterByTime,
    formatDistance,
    frequentLocations,
    greatCircleArc,
    gridDensity,
    haversineMeters,
    destinationPoint,
    nearby,
    pointInPolygon,
    searchPoints,
    stayPoints,
    timeExtent,
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

    it('interpolates great-circle arcs through the midpoint', () => {
        const arc = greatCircleArc(23.8103, 90.4125, 51.5074, -0.1278, 16);
        expect(arc).toHaveLength(17);
        expect(arc[0][0]).toBeCloseTo(90.4125, 4);
        expect(arc[16][1]).toBeCloseTo(51.5074, 4);
        // A Dhaka→London great circle bows well north of the straight-line midpoint (~37.7°).
        const midLat = arc[8][1];
        expect(midLat).toBeGreaterThan(44);
        // Degenerate zero-length arc: just the two (identical) ends.
        expect(greatCircleArc(10, 10, 10, 10)).toEqual([[10, 10], [10, 10]]);
    });

    it('computes the time extent across points and routes', () => {
        expect(timeExtent(DATA)).toEqual([100, 300]);
        expect(timeExtent({ points: [pt('x', 0, 0)], routes: [] })).toBeNull();
    });

    it('clusters co-located points, excluding singletons', () => {
        const clusters = clusterLocations(DATA.points, 2000);
        expect(clusters).toHaveLength(1);
        expect(clusters[0].sort()).toEqual(['dhk', 'dhk2']);
    });

    it('point-in-polygon via ray casting', () => {
        const box: [number, number][] = [[90, 23], [91, 23], [91, 24], [90, 24], [90, 23]];
        expect(pointInPolygon(23.5, 90.5, box)).toBe(true);
        expect(pointInPolygon(24.5, 90.5, box)).toBe(false);
        expect(pointInPolygon(23.5, 89.9, box)).toBe(false);
    });

    it('destination points and circle rings honour the radius', () => {
        const [dLat, dLon] = destinationPoint(23.81, 90.41, 90, 10_000); // 10 km due east
        expect(haversineMeters(23.81, 90.41, dLat, dLon)).toBeCloseTo(10_000, -1);
        expect(dLon).toBeGreaterThan(90.41);
        const ring = circleRing(23.81, 90.41, 5000, 32);
        expect(ring).toHaveLength(33); // closed
        expect(ring[0]).toEqual(ring[32]);
        for (const [lon, lat] of ring) {
            expect(haversineMeters(23.81, 90.41, lat, lon)).toBeCloseTo(5000, -1);
        }
        // A circle ring is a usable polygon: its center is inside.
        expect(pointInPolygon(23.81, 90.41, ring)).toBe(true);
    });

    // ── geo intelligence (Phase 3) ───────────────────────────────────────────────────────────
    const HOUR = 3_600_000;
    const track = (entity: string, legs: [number, number, number][]): GeoPoint[] =>
        legs.map(([lat, lon, h], i) => ({
            id: `${entity}-${i}`, lat, lon, kind: 'device', label: entity, time: h * HOUR,
        }));

    it('detects stay-points: a dwell within radius spanning the minimum time', () => {
        const pts = track('A', [
            [23.8100, 90.4100, 1], [23.8101, 90.4101, 3], [23.8102, 90.4100, 5], // 4h dwell
            [23.9000, 90.5000, 6], // moves away
        ]);
        const stays = stayPoints(pts, 200, 2 * HOUR);
        expect(stays).toHaveLength(1);
        expect(stays[0].entity).toBe('A');
        expect(stays[0].pointIds).toEqual(['A-0', 'A-1', 'A-2']);
        expect(stays[0].to - stays[0].from).toBe(4 * HOUR);
        // Too-short dwell → nothing
        expect(stayPoints(pts, 200, 5 * HOUR)).toHaveLength(0);
    });

    it('finds frequent locations per entity, busiest first', () => {
        const pts = [
            ...track('A', [[23.81, 90.41, 1], [23.8101, 90.4101, 4], [23.8102, 90.41, 9], [22.35, 91.78, 6]]),
            ...track('B', [[23.81, 90.41, 2], [22.35, 91.78, 3]]),
        ];
        const freq = frequentLocations(pts, 300, 2);
        expect(freq).toHaveLength(1); // only A's Dhaka spot has ≥2 visits
        expect(freq[0].entity).toBe('A');
        expect(freq[0].count).toBe(3);
    });

    it('detects repeated co-location and builds the bridge graph', () => {
        const pts = [
            ...track('A', [[23.81, 90.41, 1], [23.81, 90.41, 10], [22.35, 91.78, 20]]),
            ...track('B', [[23.8101, 90.4101, 1.5], [23.8101, 90.4101, 10.5], [24.89, 91.87, 20]]),
            ...track('C', [[23.81, 90.41, 40]]), // right place, wrong time
        ];
        const pairs = coLocations(pts, 300, HOUR);
        expect(pairs).toHaveLength(1);
        expect([pairs[0].a, pairs[0].b]).toEqual(['A', 'B']);
        expect(pairs[0].count).toBe(2);
        expect(pairs[0].firstAt).toBe(1 * HOUR);

        const g = coLocationGraph(pairs);
        expect(g.nodes.map((n) => n.id).sort()).toEqual(['entity:A', 'entity:B']);
        expect(g.edges).toHaveLength(1);
        expect(g.edges[0].data.kind).toBe('co-located · 2');
    });
});
