import { describe, expect, it } from 'vitest';
import { describeResultSet } from './result-set';
import { recommend } from './show-me';
import './plugins'; // register the built-in viz plugins so recommend() has candidates

/** R3 §4: the Result Set descriptor + its use by the Presentation Network (Show-Me). */
describe('describeResultSet', () => {
    const rows = [
        { tariff: 'gold', cost_usd: 12.5, event_time: '2026-07-01' },
        { tariff: 'silver', cost_usd: 3.0, event_time: '2026-07-02' },
        { tariff: 'gold', cost_usd: 9.9, event_time: '2026-07-03' },
    ];

    it('infers type + role from values and cardinality for dimensions', () => {
        const rs = describeResultSet(rows);
        expect(rs.rowCount).toBe(3);
        expect(rs.columns).toEqual([
            { name: 'tariff', type: 'string', role: 'dimension', cardinality: 2 },
            { name: 'cost_usd', type: 'number', role: 'measure', cardinality: undefined },
            { name: 'event_time', type: 'date', role: 'temporal', cardinality: undefined },
        ]);
    });

    it('honours hints over inference (a query keeps its dataset column roles)', () => {
        // `code` would infer as a dimension; a hint forces measure and inference is skipped.
        const rs = describeResultSet([{ code: 1 }, { code: 2 }], [{ name: 'code', type: 'number', role: 'measure' }]);
        expect(rs.columns[0]).toEqual({ name: 'code', type: 'number', role: 'measure', cardinality: undefined });
    });

    it('id-ish numeric columns are dimensions, not measures', () => {
        const rs = describeResultSet([{ account_id: 1 }, { account_id: 2 }]);
        expect(rs.columns[0].role).toBe('dimension');
    });

    it('is accepted directly by the Show-Me recommender (Presentation Network match)', () => {
        const rs = describeResultSet(rows);
        const ranked = recommend(rs).map((p) => p.meta.type);
        // A temporal + measure result set fits a time-series plugin; a table always survives.
        expect(ranked).toContain('table');
        expect(ranked.length).toBeGreaterThan(0);
    });
});
