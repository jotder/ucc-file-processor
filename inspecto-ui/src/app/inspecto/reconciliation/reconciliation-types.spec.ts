import { describe, expect, it } from 'vitest';
import {
    buildReconciliation, CompareColumn, mergeBreaks, matchedKeyCount, ReconBreak,
    resolveBreak, runReconciliation, summarize, withinTolerance,
} from './reconciliation-types';

const KEYS = ['id'];
const COST_ABS: CompareColumn = { column: 'cost_usd', toleranceType: 'absolute', tolerance: 0.02 };

const LEFT = [
    { id: 1, cost_usd: 1.8 },
    { id: 2, cost_usd: 0.3 },
    { id: 3, cost_usd: 2.6 },
    { id: 4, cost_usd: 0.1 },
];
const RIGHT = [
    { id: 1, cost_usd: 1.8 },   // clean
    { id: 2, cost_usd: 0.31 },  // within 0.02 tolerance ⇒ clean
    { id: 3, cost_usd: 2.1 },   // value break
    { id: 5, cost_usd: 0.5 },   // missing on left
];
// id 4 missing on right.

describe('withinTolerance', () => {
    it('absolute: passes inside, fails outside', () => {
        expect(withinTolerance(0.3, 0.31, COST_ABS)).toBe(true);
        expect(withinTolerance(2.6, 2.1, COST_ABS)).toBe(false);
    });
    it('exact: string-equal only', () => {
        const c: CompareColumn = { column: 'x', toleranceType: 'exact', tolerance: 0 };
        expect(withinTolerance('A', 'A', c)).toBe(true);
        expect(withinTolerance('A', 'B', c)).toBe(false);
    });
    it('percent: relative to left', () => {
        const c: CompareColumn = { column: 'x', toleranceType: 'percent', tolerance: 10 };
        expect(withinTolerance(100, 105, c)).toBe(true); // 5%
        expect(withinTolerance(100, 120, c)).toBe(false); // 20%
        expect(withinTolerance(0, 1, c)).toBe(false); // left 0 ⇒ exact required
    });
});

describe('runReconciliation', () => {
    it('produces value/missing breaks and respects tolerance', () => {
        const breaks = runReconciliation({ keyColumns: KEYS, compareColumns: [COST_ABS] }, LEFT, RIGHT);
        const byType = breaks.reduce<Record<string, number>>((m, b) => ((m[b.type] = (m[b.type] ?? 0) + 1), m), {});
        expect(byType['value_break']).toBe(1); // id 3
        expect(byType['missing_right']).toBe(1); // id 4
        expect(byType['missing_left']).toBe(1); // id 5
        expect(breaks.every((b) => b.status === 'open')).toBe(true);
        const vb = breaks.find((b) => b.type === 'value_break')!;
        expect(vb.key).toBe('3');
        expect(vb.column).toBe('cost_usd');
        expect(vb.diff).toBeCloseTo(0.5, 5);
    });

    it('matchedKeyCount counts keys on both sides', () => {
        expect(matchedKeyCount(KEYS, LEFT, RIGHT)).toBe(3); // ids 1,2,3
    });
});

describe('mergeBreaks (lifecycle)', () => {
    it('auto-closes a previous break that is gone this run', () => {
        const prev: ReconBreak[] = [{ key: '4', type: 'missing_right', status: 'open' }];
        const fresh: ReconBreak[] = []; // key 4 now matches
        const merged = mergeBreaks(prev, fresh);
        expect(merged).toHaveLength(1);
        expect(merged[0].status).toBe('auto_closed');
    });

    it('preserves a manual resolution when the break still exists', () => {
        const prev: ReconBreak[] = [{ key: '3', type: 'value_break', column: 'cost_usd', status: 'resolved', note: 'known FX gap' }];
        const fresh: ReconBreak[] = [{ key: '3', type: 'value_break', column: 'cost_usd', status: 'open' }];
        const merged = mergeBreaks(prev, fresh);
        expect(merged[0].status).toBe('resolved');
        expect(merged[0].note).toBe('known FX gap');
    });

    it('drops previously auto-closed breaks that are still gone (bounded history)', () => {
        const prev: ReconBreak[] = [{ key: '9', type: 'missing_left', status: 'auto_closed' }];
        expect(mergeBreaks(prev, [])).toHaveLength(0);
    });
});

describe('resolveBreak + summarize', () => {
    it('resolves a break by identity', () => {
        const breaks: ReconBreak[] = [{ key: '3', type: 'value_break', column: 'cost_usd', status: 'open' }];
        const out = resolveBreak(breaks, breaks[0], true, 'accepted variance');
        expect(out[0].status).toBe('resolved');
        expect(out[0].note).toBe('accepted variance');
    });

    it('summarize counts by status/type, excluding auto-closed from type tallies', () => {
        const breaks: ReconBreak[] = [
            { key: '3', type: 'value_break', status: 'open' },
            { key: '4', type: 'missing_right', status: 'resolved' },
            { key: '9', type: 'missing_left', status: 'auto_closed' },
        ];
        const s = summarize(breaks, 4, 4, 3);
        expect(s.open).toBe(1);
        expect(s.resolved).toBe(1);
        expect(s.autoClosed).toBe(1);
        expect(s.byType.value_break).toBe(1);
        expect(s.byType.missing_left).toBe(0); // the only missing_left is auto-closed
    });
});

describe('buildReconciliation', () => {
    it('slugs the name into an id and starts empty', () => {
        const r = buildReconciliation('Switch vs Billing', 'switch_cdr', 'billing_cdr', ['id'], [COST_ABS]);
        expect(r.id).toMatch(/^switch_vs_billing_[a-z0-9]{4}$/);
        expect(r.breaks).toEqual([]);
        expect(r.lastRunAt).toBeNull();
    });
});
