import { describe, expect, it } from 'vitest';
import { OfflineGeocoder, PlaceRecord, offlineGeocode } from './geo-geocoder';

const TABLE: PlaceRecord[] = [
    { name: 'Dhaka', lat: 23.81, lon: 90.41, country: 'Bangladesh' },
    { name: 'Dubai', lat: 25.2, lon: 55.27, country: 'UAE' },
    { name: 'Delhi', lat: 28.61, lon: 77.21, country: 'India' },
    { name: 'New Delhi', lat: 28.6, lon: 77.2, country: 'India' },
    { name: 'Null Island', lat: 0, lon: 0 },
    { name: 'Broken', lat: 999, lon: 999 }, // invalid coordinate — must never be returned
];

describe('offlineGeocode', () => {
    it('ranks exact match, then prefix, then substring', () => {
        const names = offlineGeocode('del', TABLE).map((r) => r.name);
        // "Delhi" (prefix) and "New Delhi" (substring) both match; prefix ranks first
        expect(names[0]).toBe('Delhi');
        expect(names).toContain('New Delhi');
    });

    it('an exact name beats a longer prefix match', () => {
        expect(offlineGeocode('delhi', TABLE)[0].name).toBe('Delhi');
    });

    it('is case-insensitive and trims, and carries the country as context', () => {
        const [hit] = offlineGeocode('  DHAKA ', TABLE);
        expect(hit).toEqual({ name: 'Dhaka', lat: 23.81, lon: 90.41, context: 'Bangladesh' });
    });

    it('returns nothing for a blank query or no match', () => {
        expect(offlineGeocode('   ', TABLE)).toEqual([]);
        expect(offlineGeocode('atlantis', TABLE)).toEqual([]);
    });

    it('skips records with an invalid WGS84 coordinate', () => {
        expect(offlineGeocode('broken', TABLE)).toEqual([]);
    });

    it('respects the result cap', () => {
        expect(offlineGeocode('a', TABLE, 1).length).toBeLessThanOrEqual(1);
    });

    it('OfflineGeocoder resolves asynchronously against its table', async () => {
        const g = new OfflineGeocoder(TABLE);
        expect(g.id).toBe('offline');
        await expect(g.geocode('dubai')).resolves.toEqual([
            { name: 'Dubai', lat: 25.2, lon: 55.27, context: 'UAE' },
        ]);
    });
});
