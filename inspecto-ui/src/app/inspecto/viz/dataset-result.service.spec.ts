import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { environment } from '../../../environments/environment';
import { BiQueryService } from 'app/inspecto/api/bi-query.service';
import { QuerySpec } from './viz-types';
import { biQueryBody, DatasetResultService } from './dataset-result.service';

const ROWS = [{ tariff: 'gold', duration_s: 10 }, { tariff: 'gold', duration_s: 20 }];
const COLS = [
    { name: 'tariff', type: 'string' as const },
    { name: 'duration_s', type: 'number' as const },
];

function spec(overrides: Partial<QuerySpec> = {}): QuerySpec {
    return {
        datasetId: 'cdr_sample',
        sourceName: 'cdr',
        groupBy: ['tariff'],
        measures: [{ id: 'sum_duration_s', expression: 'SUM("duration_s")', label: 'sum(duration_s)', agg: 'sum', field: 'duration_s' }],
        filters: null,
        ...overrides,
    };
}

/** One TestBed per test (house rule); the BiQueryService stub records live-path calls. */
function setup(biRun: ReturnType<typeof vi.fn> = vi.fn()) {
    TestBed.configureTestingModule({
        providers: [{ provide: BiQueryService, useValue: { run: biRun } }],
    });
    return { svc: TestBed.inject(DatasetResultService), biRun };
}

describe('DatasetResultService', () => {
    it('dedupes: two calls with an identical spec (different object identity) share one run', () => {
        const { svc } = setup();
        const p1 = svc.run(spec(), ROWS);
        const p2 = svc.run(spec(), ROWS); // structurally identical, but a fresh object literal
        expect(p1).toBe(p2);
    });

    it('does not dedupe across a different spec (e.g. a different filter)', () => {
        const { svc } = setup();
        const p1 = svc.run(spec(), ROWS);
        const p2 = svc.run(spec({ groupBy: ['msisdn'] }), ROWS);
        expect(p1).not.toBe(p2);
    });

    it('still returns the cached run after it resolves (not just while in flight)', async () => {
        const { svc } = setup();
        const p1 = svc.run(spec(), ROWS);
        await p1;
        const p2 = svc.run(spec(), ROWS);
        expect(p1).toBe(p2);
    });

    it('clear() drops the cache, so the next identical spec runs fresh', async () => {
        const { svc } = setup();
        const p1 = svc.run(spec(), ROWS);
        await p1;
        svc.clear();
        const p2 = svc.run(spec(), ROWS);
        expect(p1).not.toBe(p2);
    });

    it('mock mode (mockStudio=true) stays offline — the BI endpoint is never called', async () => {
        const { svc, biRun } = setup();
        await svc.run(spec(), ROWS, COLS);
        expect(biRun).not.toHaveBeenCalled();
    });

    // ── M2 live branch (mockStudio=false → POST /bi/query) ─────────────────────

    it('live mode maps the spec to the wire body and returns the server rows', async () => {
        const biRun = vi.fn(() => of({ rows: [{ tariff: 'gold', sum_duration_s: 30 }] }));
        const { svc } = setup(biRun);
        environment.mockStudio = false;
        try {
            const res = await svc.run(spec(), ROWS, COLS);
            expect(res.ok).toBe(true);
            expect(res.rows).toEqual([{ tariff: 'gold', sum_duration_s: 30 }]);
            expect(biRun).toHaveBeenCalledWith({
                dataset: 'cdr_sample',
                measures: [{ agg: 'sum', field: 'duration_s' }],
                groupBy: ['tariff'],
            });
        } finally {
            environment.mockStudio = true;
        }
    });

    it('live mode maps a server error to an ok:false result (never throws)', async () => {
        const biRun = vi.fn(() => throwError(() => ({ status: 422, error: { error: 'bad spec' } })));
        const { svc } = setup(biRun);
        environment.mockStudio = false;
        try {
            const res = await svc.run(spec(), ROWS, COLS);
            expect(res.ok).toBe(false);
            expect(res.error).toBeTruthy();
        } finally {
            environment.mockStudio = true;
        }
    });

    it('live mode fails honestly on an unmappable spec without calling the endpoint', async () => {
        const { svc, biRun } = setup();
        environment.mockStudio = false;
        try {
            // A named-measure expression has no structured {agg, field} origin — offline-only.
            const res = await svc.run(
                spec({ measures: [{ id: 'avg_cost', expression: 'SUM(x)/COUNT(*)', label: 'avg cost' }] }),
                ROWS, COLS,
            );
            expect(res.ok).toBe(false);
            expect(res.error).toContain('offline-only');
            expect(biRun).not.toHaveBeenCalled();
        } finally {
            environment.mockStudio = true;
        }
    });
});

describe('biQueryBody', () => {
    it('maps measures/groupBy/orderBy/limit and types filter values by column', () => {
        const body = biQueryBody(spec({
            filters: {
                kind: 'group', op: 'AND', items: [
                    { kind: 'condition', field: 'tariff', operator: '=', value: 'gold' },
                    { kind: 'condition', field: 'duration_s', operator: '>=', value: '10' },
                ],
            },
            orderBy: [{ field: 'tariff', dir: 'asc' }],
            limit: 100,
        }), COLS);
        expect(body).toEqual({
            dataset: 'cdr_sample',
            measures: [{ agg: 'sum', field: 'duration_s' }],
            groupBy: ['tariff'],
            filters: [
                { field: 'tariff', op: '=', value: 'gold' },
                { field: 'duration_s', op: '>=', value: 10 },   // number, not '10'
            ],
            orderBy: [{ field: 'tariff', dir: 'asc' }],
            limit: 100,
        });
    });

    it('maps count without a field, contains→like, between→two terms, in→typed list', () => {
        const body = biQueryBody(spec({
            measures: [{ id: 'count', expression: 'COUNT(*)', label: 'count', agg: 'count' }],
            filters: {
                kind: 'group', op: 'AND', items: [
                    { kind: 'condition', field: 'tariff', operator: 'contains', value: 'ol' },
                    { kind: 'condition', field: 'duration_s', operator: 'between', value: '5', value2: '50' },
                    { kind: 'condition', field: 'duration_s', operator: 'in', value: '10, 20' },
                ],
            },
        }), COLS);
        expect(body?.measures).toEqual([{ agg: 'count' }]);
        expect(body?.filters).toEqual([
            { field: 'tariff', op: 'like', value: '%ol%' },
            { field: 'duration_s', op: '>=', value: 5 },
            { field: 'duration_s', op: '<=', value: 50 },
            { field: 'duration_s', op: 'in', value: [10, 20] },
        ]);
    });

    it('flattens nested AND groups; a single-item OR is that item', () => {
        const body = biQueryBody(spec({
            filters: {
                kind: 'group', op: 'AND', items: [
                    { kind: 'condition', field: 'tariff', operator: 'isNotNull' },
                    {
                        kind: 'group', op: 'OR', items: [
                            { kind: 'condition', field: 'tariff', operator: '!=', value: 'silver' },
                        ],
                    },
                ],
            },
        }), COLS);
        expect(body?.filters).toEqual([
            { field: 'tariff', op: 'notNull' },
            { field: 'tariff', op: '!=', value: 'silver' },
        ]);
    });

    it('is null for a real OR branch — dropping a term would change the numbers', () => {
        expect(biQueryBody(spec({
            filters: {
                kind: 'group', op: 'OR', items: [
                    { kind: 'condition', field: 'tariff', operator: '=', value: 'gold' },
                    { kind: 'condition', field: 'tariff', operator: '=', value: 'silver' },
                ],
            },
        }), COLS)).toBeNull();
    });

    it('is null for a named-measure expression and for an empty projection', () => {
        expect(biQueryBody(spec({ measures: [{ id: 'm', expression: 'SUM(x)', label: 'm' }] }), COLS)).toBeNull();
        expect(biQueryBody(spec({ measures: [], groupBy: [] }), COLS)).toBeNull();
    });
});
