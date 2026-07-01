import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { QuerySpec } from './viz-types';
import { DatasetResultService } from './dataset-result.service';

const ROWS = [{ tariff: 'gold', duration_s: 10 }, { tariff: 'gold', duration_s: 20 }];

function spec(overrides: Partial<QuerySpec> = {}): QuerySpec {
    return {
        datasetId: 'cdr_sample',
        sourceName: 'cdr',
        groupBy: ['tariff'],
        measures: [{ id: 'sum_duration_s', expression: 'SUM("duration_s")', label: 'sum(duration_s)' }],
        filters: null,
        ...overrides,
    };
}

describe('DatasetResultService', () => {
    it('dedupes: two calls with an identical spec (different object identity) share one run', () => {
        const svc = TestBed.inject(DatasetResultService);
        const p1 = svc.run(spec(), ROWS);
        const p2 = svc.run(spec(), ROWS); // structurally identical, but a fresh object literal
        expect(p1).toBe(p2);
    });

    it('does not dedupe across a different spec (e.g. a different filter)', () => {
        const svc = TestBed.inject(DatasetResultService);
        const p1 = svc.run(spec(), ROWS);
        const p2 = svc.run(spec({ groupBy: ['msisdn'] }), ROWS);
        expect(p1).not.toBe(p2);
    });

    it('still returns the cached run after it resolves (not just while in flight)', async () => {
        const svc = TestBed.inject(DatasetResultService);
        const p1 = svc.run(spec(), ROWS);
        await p1;
        const p2 = svc.run(spec(), ROWS);
        expect(p1).toBe(p2);
    });

    it('clear() drops the cache, so the next identical spec runs fresh', async () => {
        const svc = TestBed.inject(DatasetResultService);
        const p1 = svc.run(spec(), ROWS);
        await p1;
        svc.clear();
        const p2 = svc.run(spec(), ROWS);
        expect(p1).not.toBe(p2);
    });
});
