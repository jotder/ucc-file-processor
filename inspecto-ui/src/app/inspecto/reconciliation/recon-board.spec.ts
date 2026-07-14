import { describe, expect, it } from 'vitest';
import { ICellRendererParams } from 'ag-grid-community';
import { CompareColumn } from './reconciliation-types';
import {
    aggregateRecon, bandCell, bandFor, buildBoardTree, decodePath, deltaPct, encodePath,
    markBreachesExpanded, RECON_RECORDS, reconBreakSets, ReconRunResult,
} from './recon-board';

/**
 * Offline-engine parity with the backend `ReconServiceTest` — the SAME reference fixture
 * (Mediation-vs-Billing): EU/voice matched-equal (A 2×100 vs B 1×200), EU/data matched outside the
 * 0.5% tolerance (118 vs 114 = 3.39%), US/voice matched-equal, MEA/voice only in A, APAC/sms only in B.
 */
const LEFT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 118 },
    { region: 'US', product: 'voice', amount: 50 },
    { region: 'MEA', product: 'voice', amount: 10 },
];
const RIGHT = [
    { region: 'EU', product: 'voice', amount: 200 },
    { region: 'EU', product: 'data', amount: 114 },
    { region: 'US', product: 'voice', amount: 50 },
    { region: 'APAC', product: 'sms', amount: 7 },
];
const CONFIG = {
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 } as CompareColumn],
};

const run = (): ReconRunResult => aggregateRecon(CONFIG, LEFT, RIGHT);

function grain(r: ReconRunResult, region: string, product: string) {
    const row = r.rows.find((x) => x.key['region'] === region && x.key['product'] === product);
    expect(row, `${region}/${product}`).toBeDefined();
    return row!;
}

describe('aggregateRecon (backend ReconService parity)', () => {
    it('computes grain rows, exact totals, and the break summary', () => {
        const r = run();
        expect(r.rows).toHaveLength(5);
        expect(r.measures).toEqual(['amount', RECON_RECORDS]);

        const euData = grain(r, 'EU', 'data');
        expect(euData.a['amount']).toBe(118);
        expect(euData.b['amount']).toBe(114);
        expect(euData.inA && euData.inB).toBe(true);

        const euVoice = grain(r, 'EU', 'voice');
        expect(euVoice.a['amount']).toBe(200);
        expect(euVoice.a[RECON_RECORDS]).toBe(2);

        const mea = grain(r, 'MEA', 'voice');
        expect(mea.inB).toBe(false);
        expect(mea.b['amount']).toBeNull();

        expect(r.totals.a['amount']).toBe(378);
        expect(r.totals.b['amount']).toBe(371);
        expect(r.totals.a[RECON_RECORDS]).toBe(5);
        expect(r.totals.b[RECON_RECORDS]).toBe(4);

        expect(r.summary.groups).toBe(5);
        expect(r.summary.matchedKeys).toBe(3);
        expect(r.summary.byType).toEqual({ missing_left: 1, missing_right: 1, value_break: 1 });
    });

    it('ports withinTolerance over aggregates: boundaries are within, zero anchor requires equality', () => {
        const vb = (a: number, b: number, c: Partial<CompareColumn>): number =>
            aggregateRecon(
                { keyColumns: ['k'], compareColumns: [{ column: 'v', toleranceType: 'exact', tolerance: 0, ...c } as CompareColumn] },
                [{ k: 'x', v: a }], [{ k: 'x', v: b }],
            ).summary.byType.value_break;
        expect(vb(100, 105, { toleranceType: 'absolute', tolerance: 5 })).toBe(0);
        expect(vb(100, 106, { toleranceType: 'absolute', tolerance: 5 })).toBe(1);
        expect(vb(100, 100.5, { toleranceType: 'percent', tolerance: 0.5 })).toBe(0);
        expect(vb(100, 100.6, { toleranceType: 'percent', tolerance: 0.5 })).toBe(1);
        expect(vb(0, 0, { toleranceType: 'percent', tolerance: 50 })).toBe(0);
        expect(vb(0, 1, { toleranceType: 'percent', tolerance: 50 })).toBe(1);
    });

    it('groups NULL key values together (both-null keys match)', () => {
        const r = aggregateRecon(
            { keyColumns: ['k'], compareColumns: [{ column: 'v', toleranceType: 'exact', tolerance: 0 }] },
            [{ k: null, v: 1 }, { k: 'x', v: 2 }],
            [{ k: null, v: 1 }],
        );
        expect(r.summary.groups).toBe(2);
        expect(r.summary.matchedKeys).toBe(1);
        expect(r.summary.byType.missing_right).toBe(1);
        expect(r.summary.byType.value_break).toBe(0);
    });
});

describe('reconBreakSets', () => {
    it('splits the three sets, scopes by path, and filters by type', () => {
        const all = reconBreakSets(CONFIG, LEFT, RIGHT);
        expect(all.missing_right!.rows.map((r) => r.key['region'])).toEqual(['MEA']);
        expect(all.missing_right!.rows[0].a?.['amount']).toBe(10);
        expect(all.missing_right!.rows[0].b).toBeUndefined();
        expect(all.missing_left!.rows.map((r) => r.key['region'])).toEqual(['APAC']);
        expect(all.value_break!.rows.map((r) => r.key['product'])).toEqual(['data']);

        const eu = reconBreakSets(CONFIG, LEFT, RIGHT, { region: 'EU' });
        expect(eu.missing_right!.rowCount).toBe(0);
        expect(eu.value_break!.rowCount).toBe(1);

        const one = reconBreakSets(CONFIG, LEFT, RIGHT, null, 'value_break');
        expect(Object.keys(one)).toEqual(['value_break']);
    });
});

describe('buildBoardTree', () => {
    it('rolls parents up from SUMS (never averaged child Δ%s) and marks one-sided nodes structural', () => {
        const nodes = buildBoardTree(run());
        const eu = nodes.find((n) => n.label === 'EU')!;
        expect(eu.values!['a_amount']).toBe(318);   // 200 + 118
        expect(eu.values!['b_amount']).toBe(314);   // 200 + 114
        // Δ% from rolled sums: (314-318)/318·100 = −1.258… — NOT the child average (0 + −3.39)/2.
        expect(eu.values!['pct_amount']).toBeCloseTo(-1.2579, 3);
        expect(eu.values!['__structural']).toBeNull();

        // children sorted worst-severity first: BOTH breach — voice on the implicit record count
        // (2 vs 1 rows = −50%) outranks data (amount −3.39%) by |Δ%| within the breach band.
        expect(eu.children!.map((c) => c.label)).toEqual(['voice', 'data']);
        expect(eu.children![0].values!['pct___records']).toBeCloseTo(-50);

        const mea = nodes.find((n) => n.label === 'MEA')!;
        expect(mea.values!['__structural']).toBe('a');
        expect(mea.values!['b_amount']).toBeNull();

        // drill path ids encode the dimension path in key-column order
        expect(eu.children![1].id).toBe('region:EU|product:data');
    });

    it('markBreachesExpanded expands exactly the ancestors of hot nodes', () => {
        const marked = markBreachesExpanded(buildBoardTree(run()));
        const eu = marked.find((n) => n.label === 'EU')!;
        const us = marked.find((n) => n.label === 'US')!;
        expect(eu.expanded).toBe(true);        // contains the EU/data breach
        expect(us.expanded).toBeUndefined();   // all-ok subtree stays collapsed
    });
});

describe('bands + cells + paths', () => {
    it('bandFor honors the locked defaults (<1 ok · 1–2 warn · >2 breach; null = structural)', () => {
        expect(bandFor(0.99)).toBe('ok');
        expect(bandFor(-0.99)).toBe('ok');
        expect(bandFor(1)).toBe('warn');
        expect(bandFor(2)).toBe('warn');
        expect(bandFor(2.01)).toBe('breach');
        expect(bandFor(null)).toBe('structural');
    });

    it('deltaPct is anchor-relative and null for a zero anchor with a non-zero other', () => {
        expect(deltaPct(100, 101)).toBeCloseTo(1);
        expect(deltaPct(0, 0)).toBe(0);
        expect(deltaPct(0, 5)).toBeNull();
        expect(deltaPct(null, 5)).toBeNull();
    });

    it('bandCell renders glyph + text (never color alone) and names the one-sided dataset', () => {
        const cell = bandCell();
        const p = (value: unknown, data?: Record<string, unknown>): ICellRendererParams =>
            ({ value, data }) as unknown as ICellRendererParams;
        expect(cell(p(0.3))).toContain('✓ +0.3%');
        expect(cell(p(-1.6))).toContain('! -1.6%');
        expect(cell(p(3.4))).toContain('✕ +3.4%');
        expect(cell(p(null, { __structural: 'a' }))).toContain('⊘ only in A');
        expect(cell(p(null))).toContain('✕ new');
    });

    it('encodePath/decodePath round-trip values with reserved characters', () => {
        const enc = encodePath({ region: 'EU|x', product: 'a:b' }, ['region', 'product']);
        expect(decodePath(enc)).toEqual({ region: 'EU|x', product: 'a:b' });
        expect(decodePath('')).toBeNull();
    });
});
