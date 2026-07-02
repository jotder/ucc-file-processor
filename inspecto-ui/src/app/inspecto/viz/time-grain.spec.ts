import { describe, expect, it } from 'vitest';
import { bucketRows, bucketValue } from './time-grain';

describe('time-grain', () => {
    it('buckets to day / month', () => {
        expect(bucketValue('2026-06-24 09:01:30', 'day')).toBe('2026-06-24');
        expect(bucketValue('2026-06-24 09:01:30', 'month')).toBe('2026-06');
    });

    it('buckets week to its Monday', () => {
        expect(bucketValue('2026-06-24 12:00:00', 'week')).toBe('2026-06-22'); // Wed → Mon
        expect(bucketValue('2026-06-22 00:00:00', 'week')).toBe('2026-06-22'); // Mon stays
        expect(bucketValue('2026-06-28 23:59:00', 'week')).toBe('2026-06-22'); // Sun → prior Mon
    });

    it('passes through auto, null, and unparseable values', () => {
        expect(bucketValue('2026-06-24', 'auto')).toBe('2026-06-24');
        expect(bucketValue(null, 'day')).toBeNull();
        expect(bucketValue('not-a-date', 'day')).toBe('not-a-date');
    });

    it('bucketRows rewrites only the given field; auto/undefined is a no-op returning the same array', () => {
        const rows = [{ t: '2026-06-24 09:00:00', v: 1 }];
        expect(bucketRows(rows, 't', 'month')).toEqual([{ t: '2026-06', v: 1 }]);
        expect(bucketRows(rows, 't', 'auto')).toBe(rows);
        expect(bucketRows(rows, 't', undefined)).toBe(rows);
    });
});
