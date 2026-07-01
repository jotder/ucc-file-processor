import { describe, expect, it } from 'vitest';
import { buildWidget } from './widget-types';

describe('buildWidget', () => {
    it('uses the name as id and carries dataset/viz/controls', () => {
        const controls = { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' as const }] };
        const w = buildWidget('dur_by_tariff', 'cdr_sample', 'bar', controls);
        expect(w.id).toBe('dur_by_tariff');
        expect(w.datasetId).toBe('cdr_sample');
        expect(w.vizType).toBe('bar');
        expect(w.controls).toBe(controls);
    });
});
