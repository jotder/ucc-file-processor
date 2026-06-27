import { describe, expect, it } from 'vitest';
import { quickFilterRows } from './quick-filter';

const ROWS = [
    { id: 1, name: 'alpha' },
    { id: 2, name: 'beta' },
    { id: 3, name: 'gamma' },
];

describe('quickFilterRows', () => {
    it('returns a copy of all rows for empty text', () => {
        const r = quickFilterRows(ROWS, '');
        expect(r.length).toBe(3);
        expect(r).not.toBe(ROWS);
    });

    it('matches case-insensitively across all columns', () => {
        expect(quickFilterRows(ROWS, 'ET').map((r) => r.id)).toEqual([2]);
    });

    it('restricts the match to the given columns', () => {
        expect(quickFilterRows(ROWS, '2', ['name']).length).toBe(0);
        expect(quickFilterRows(ROWS, '2', ['id']).map((r) => r.id)).toEqual([2]);
    });
});
