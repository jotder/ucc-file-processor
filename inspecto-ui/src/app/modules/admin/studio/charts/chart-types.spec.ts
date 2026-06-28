import { describe, expect, it } from 'vitest';
import { buildChart } from './chart-types';

describe('buildChart', () => {
    it('uses the name as id and carries dataset/viz/controls', () => {
        const controls = { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' as const }] };
        const c = buildChart('dur_by_tariff', 'cdr_sample', 'bar', controls);
        expect(c.id).toBe('dur_by_tariff');
        expect(c.datasetId).toBe('cdr_sample');
        expect(c.vizType).toBe('bar');
        expect(c.controls).toBe(controls);
    });
});
