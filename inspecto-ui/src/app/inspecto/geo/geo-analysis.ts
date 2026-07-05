import { GeoData, GeoPoint } from './geo-types';

/**
 * Pure spatial analysis over {@link GeoData} — the geo analog of `inspecto/graph/graph-analysis.ts`:
 * framework-free, canvas-free, fully unit-testable. All coordinates are WGS84 degrees; all
 * distances are meters (great-circle / haversine — no routing engine, by design; see
 * docs/superpower/geo-map-analysis-plan.md).
 */

/** Guard for super-linear algorithms (mirrors graph-analysis's ANALYSIS_NODE_CAP). */
export const ANALYSIS_POINT_CAP = 5000;

const EARTH_RADIUS_M = 6_371_000;
const rad = (deg: number): number => (deg * Math.PI) / 180;

/** True when the pair is a plausible WGS84 coordinate (finite, in range). */
export function validCoordinate(lat: unknown, lon: unknown): boolean {
    return (
        typeof lat === 'number' && Number.isFinite(lat) && lat >= -90 && lat <= 90 &&
        typeof lon === 'number' && Number.isFinite(lon) && lon >= -180 && lon <= 180
    );
}

/** Great-circle (haversine) distance in meters. */
export function haversineMeters(aLat: number, aLon: number, bLat: number, bLon: number): number {
    const dLat = rad(bLat - aLat);
    const dLon = rad(bLon - aLon);
    const s =
        Math.sin(dLat / 2) ** 2 +
        Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLon / 2) ** 2;
    return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(s));
}

/** Human distance label: `847 m` under 1 km, else `12.4 km`. */
export function formatDistance(meters: number): string {
    return meters < 1000 ? `${Math.round(meters)} m` : `${(meters / 1000).toFixed(1)} km`;
}

/**
 * Great-circle arc between two coordinates as [lon, lat] steps (inclusive ends) — spherical
 * linear interpolation, so long routes bow correctly instead of cutting straight across.
 */
export function greatCircleArc(
    aLat: number, aLon: number, bLat: number, bLon: number, steps = 32,
): [number, number][] {
    const φ1 = rad(aLat), λ1 = rad(aLon), φ2 = rad(bLat), λ2 = rad(bLon);
    const toVec = (φ: number, λ: number): [number, number, number] =>
        [Math.cos(φ) * Math.cos(λ), Math.cos(φ) * Math.sin(λ), Math.sin(φ)];
    const [x1, y1, z1] = toVec(φ1, λ1);
    const [x2, y2, z2] = toVec(φ2, λ2);
    const ω = Math.acos(Math.min(1, Math.max(-1, x1 * x2 + y1 * y2 + z1 * z2)));
    // acos() noise near identical points is ~1e-8 — short-circuit well above it (1e-6 rad ≈ 6 m).
    if (ω < 1e-6) return [[aLon, aLat], [bLon, bLat]];
    const out: [number, number][] = [];
    for (let i = 0; i <= steps; i++) {
        const t = i / steps;
        const A = Math.sin((1 - t) * ω) / Math.sin(ω);
        const B = Math.sin(t * ω) / Math.sin(ω);
        const x = A * x1 + B * x2, y = A * y1 + B * y2, z = A * z1 + B * z2;
        out.push([
            (Math.atan2(y, x) * 180) / Math.PI,
            (Math.atan2(z, Math.sqrt(x * x + y * y)) * 180) / Math.PI,
        ]);
    }
    return out;
}

/** The [min, max] epoch-millis extent of the timed points/routes, or `null` when nothing is timed. */
export function timeExtent(data: GeoData): [number, number] | null {
    let min = Infinity, max = -Infinity;
    for (const t of [...data.points, ...data.routes].map((x) => x.time)) {
        if (t === undefined) continue;
        if (t < min) min = t;
        if (t > max) max = t;
    }
    return min === Infinity ? null : [min, max];
}

/** A [west, south, east, north] bounding box in degrees. */
export type GeoBBox = [number, number, number, number];

/** The tight bounding box of the points, or `null` when empty. */
export function boundsOf(points: readonly GeoPoint[]): GeoBBox | null {
    if (!points.length) return null;
    let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
    for (const p of points) {
        if (p.lon < w) w = p.lon;
        if (p.lon > e) e = p.lon;
        if (p.lat < s) s = p.lat;
        if (p.lat > n) n = p.lat;
    }
    return [w, s, e, n];
}

/** Points inside the box (inclusive edges). */
export function withinBBox(points: readonly GeoPoint[], [w, s, e, n]: GeoBBox): GeoPoint[] {
    return points.filter((p) => p.lon >= w && p.lon <= e && p.lat >= s && p.lat <= n);
}

/** Points within `radiusM` meters of the center, nearest first (each with its distance). */
export function nearby(
    points: readonly GeoPoint[],
    lat: number,
    lon: number,
    radiusM: number,
): { point: GeoPoint; distanceM: number }[] {
    return points
        .map((point) => ({ point, distanceM: haversineMeters(lat, lon, point.lat, point.lon) }))
        .filter((r) => r.distanceM <= radiusM)
        .sort((a, b) => a.distanceM - b.distanceM);
}

/** Case-insensitive point search on label, id and kind (mirrors graph-analysis's searchNodes). */
export function searchPoints(data: GeoData, text: string): string[] {
    const q = text.trim().toLowerCase();
    if (!q) return [];
    return data.points
        .filter(
            (p) =>
                p.id.toLowerCase().includes(q) ||
                (p.label ?? '').toLowerCase().includes(q) ||
                p.kind.toLowerCase().includes(q),
        )
        .map((p) => p.id);
}

/** The subset with only the given point kinds (and the routes both of whose ends survive). */
export function filterByKinds(data: GeoData, pointKinds: readonly string[] | null): GeoData {
    if (!pointKinds) return data;
    const kinds = new Set(pointKinds);
    const points = data.points.filter((p) => kinds.has(p.kind));
    const keep = new Set(points.map((p) => p.id));
    return { points, routes: data.routes.filter((r) => keep.has(r.from) && keep.has(r.to)) };
}

/** The subset whose `time` falls in [from, to] (points without a time always survive). */
export function filterByTime(data: GeoData, from: number | null, to: number | null): GeoData {
    const inRange = (t: number | undefined): boolean =>
        t === undefined || ((from === null || t >= from) && (to === null || t <= to));
    const points = data.points.filter((p) => inRange(p.time));
    const keep = new Set(points.map((p) => p.id));
    return {
        points,
        routes: data.routes.filter((r) => inRange(r.time) && keep.has(r.from) && keep.has(r.to)),
    };
}

/** One cell of a density grid: cell-center coordinates + the points that fell in. */
export interface DensityCell {
    lat: number;
    lon: number;
    count: number;
    pointIds: string[];
}

/**
 * Square-grid density binning (the heatmap/hotspot seam): `cellDeg` degrees per cell,
 * cells with at least one point, densest first.
 */
export function gridDensity(points: readonly GeoPoint[], cellDeg: number): DensityCell[] {
    const cells = new Map<string, DensityCell>();
    for (const p of points) {
        const gy = Math.floor(p.lat / cellDeg);
        const gx = Math.floor(p.lon / cellDeg);
        const key = `${gx}:${gy}`;
        let cell = cells.get(key);
        if (!cell) {
            cell = { lat: (gy + 0.5) * cellDeg, lon: (gx + 0.5) * cellDeg, count: 0, pointIds: [] };
            cells.set(key, cell);
        }
        cell.count++;
        cell.pointIds.push(p.id);
    }
    return [...cells.values()].sort((a, b) => b.count - a.count);
}

/**
 * Location clustering: greedy DBSCAN-style grouping — points within `radiusM` of a cluster
 * seed join it. Returns point-id groups, biggest first; singletons excluded. Capped at
 * {@link ANALYSIS_POINT_CAP} points (O(n²) worst case).
 */
export function clusterLocations(points: readonly GeoPoint[], radiusM: number): string[][] {
    const pts = points.slice(0, ANALYSIS_POINT_CAP);
    const assigned = new Set<string>();
    const clusters: string[][] = [];
    for (const seed of pts) {
        if (assigned.has(seed.id)) continue;
        const members = [seed.id];
        assigned.add(seed.id);
        for (const p of pts) {
            if (assigned.has(p.id)) continue;
            if (haversineMeters(seed.lat, seed.lon, p.lat, p.lon) <= radiusM) {
                members.push(p.id);
                assigned.add(p.id);
            }
        }
        if (members.length > 1) clusters.push(members);
    }
    return clusters.sort((a, b) => b.length - a.length);
}
