import { describe, expect, it } from 'vitest';
import { SAMPLE_SOURCES } from 'app/modules/admin/studio/datasets/dataset-sources';
import {
    coLocations,
    frequentLocations,
    haversineMeters,
    stayPoints,
    timeExtent,
    validCoordinate,
} from 'app/inspecto/geo';
import { GEO_POINT_CAP, isGeoProjectionError, projectPoints, projectRoutes } from './geo-projection';

/**
 * Invariants for the CS1–CS5 case-study datasets (docs/superpower/geo-map-case-studies.md).
 * Each case exists to push one boundary — these specs pin that the generated data actually
 * exercises it, so a future edit can't silently defuse a case study.
 */

const HOUR = 3_600_000;

function project(source: string, p: { entityCol: string; kindCol: string; timeCol: string }) {
    const out = projectPoints(SAMPLE_SOURCES[source], { datasetId: source, latCol: 'lat', lonCol: 'lon', ...p });
    if (isGeoProjectionError(out)) throw new Error(out.error);
    return out;
}

describe('geo case studies (CS1–CS5)', () => {
    it('CS1 simbox_sweep trips the point cap AND the skip banner; farms dwell', () => {
        const rows = SAMPLE_SOURCES['simbox_sweep'];
        expect(rows.length).toBeGreaterThan(GEO_POINT_CAP + 500);
        const out = project('simbox_sweep', { entityCol: 'msisdn', kindCol: 'role', timeCol: 'event_time' });
        expect(out.truncated).toBe(true);
        expect(out.points).toHaveLength(GEO_POINT_CAP);
        expect(out.skipped).toBe(25); // the leading broken rows
        // Farm SIMs are static → stay-point detection fires on the farm points.
        const farmPoints = out.points.filter((p) => p.kind === 'simbox');
        expect(farmPoints.length).toBeGreaterThan(1000);
        const stays = stayPoints(farmPoints.slice(0, 600), 100, 2 * HOUR);
        expect(stays.length).toBeGreaterThan(10);
    });

    it('CS2 impossible_travel contains exactly one physically impossible hop', () => {
        const out = project('impossible_travel', { entityCol: 'account', kindCol: 'channel', timeCol: 'login_time' });
        expect(out.skipped).toBe(0);
        // Per account: max speed between consecutive logins. Only ACC-007 breaks Mach 1.5 (~1800 km/h).
        const byAccount = new Map<string, typeof out.points>();
        for (const p of out.points) byAccount.set(p.label!, [...(byAccount.get(p.label!) ?? []), p]);
        const speeders: string[] = [];
        for (const [account, pts] of byAccount) {
            pts.sort((a, b) => a.time! - b.time!);
            for (let i = 1; i < pts.length; i++) {
                const km = haversineMeters(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon) / 1000;
                const h = (pts[i].time! - pts[i - 1].time!) / HOUR;
                if (h > 0 && km / h > 1800 && !speeders.includes(account)) speeders.push(account);
            }
        }
        expect(speeders).toEqual(['ACC-007']);
    });

    it('CS3 mule_corridors folds ~900 legs into 24 weighted corridors, 5 skipped', () => {
        const rows = SAMPLE_SOURCES['mule_corridors'];
        expect(rows.length).toBeGreaterThan(850);
        const out = projectRoutes(rows, {
            datasetId: 'mule_corridors', fromLatCol: 'from_lat', fromLonCol: 'from_lon',
            toLatCol: 'to_lat', toLonCol: 'to_lon', fromCol: 'from_city', toCol: 'to_city',
            kindCol: 'channel', timeCol: 'moved_at',
        });
        if (isGeoProjectionError(out)) throw new Error(out.error);
        expect(out.routes).toHaveLength(24);
        expect(out.skipped).toBe(5);
        const weights = out.routes.map((r) => r.weight ?? 1);
        expect(Math.max(...weights)).toBe(150); // Dhaka→Dubai hundi
        expect(Math.min(...weights)).toBe(3);
        expect(new Set(out.routes.map((r) => r.kind))).toEqual(new Set(['hundi', 'wire', 'crypto', 'cash']));
        expect(out.points).toHaveLength(18); // one endpoint per city
        // The whole week is timed → the studio's timeline appears.
        expect(timeExtent(out)).not.toBeNull();
    });

    it('CS4 fleet_breadcrumbs: the two staged roadside dwells and the depots are detectable', () => {
        const out = project('fleet_breadcrumbs', { entityCol: 'truck', kindCol: 'status', timeCol: 'ping_time' });
        expect(out.points.length).toBeGreaterThan(500);
        expect(out.points.every((p) => validCoordinate(p.lat, p.lon))).toBe(true);
        const stays = stayPoints(out.points, 300, 45 * 60_000);
        // Home/away depot dwells for all six trucks plus the two staged roadside stops.
        expect(stays.length).toBeGreaterThanOrEqual(14);
        const roadside = stays.filter((s) => ['TRK-02', 'TRK-05'].includes(s.entity) && s.to - s.from <= 90 * 60_000);
        expect(roadside.length).toBeGreaterThanOrEqual(2);
        const freq = frequentLocations(out.points, 300);
        expect(new Set(freq.map((f) => f.entity)).size).toBe(6); // every truck has a frequent spot
    });

    it('CS5 border_roamers: staged meetings surface as repeated co-locations at the crossings', () => {
        const out = project('border_roamers', { entityCol: 'imei', kindCol: 'side', timeCol: 'seen_at' });
        expect(out.points.length).toBeGreaterThan(690);
        expect(new Set(out.points.map((p) => p.kind))).toEqual(new Set(['india', 'bangladesh']));
        const pairs = coLocations(out.points, 300, 30 * 60_000);
        const b01b07 = pairs.find((p) => p.a === 'IMEI-B01' && p.b === 'IMEI-B07');
        const b03b11 = pairs.find((p) => p.a === 'IMEI-B03' && p.b === 'IMEI-B11');
        expect(b01b07?.count).toBeGreaterThanOrEqual(3);
        expect(b03b11?.count).toBeGreaterThanOrEqual(2);
    });

    it('all five case-study sources are deterministic module constants (same reference on re-read)', () => {
        for (const id of ['simbox_sweep', 'impossible_travel', 'mule_corridors', 'fleet_breadcrumbs', 'border_roamers']) {
            expect(SAMPLE_SOURCES[id]).toBe(SAMPLE_SOURCES[id]);
            expect(SAMPLE_SOURCES[id].length).toBeGreaterThan(0);
        }
    });
});
