import { describe, expect, it } from 'vitest';
import { ControlValues } from '../viz-types';
import { buildValueQuery, buildXyQuery, transformValue, transformXy } from './plugin-helpers';

const CTX = { datasetId: 'cdr', sourceName: 'cdr' };

describe('buildXyQuery', () => {
    it('groups by x (+series) and aggregates y', () => {
        const values: ControlValues = {
            x: [{ field: 'tariff' }],
            series: [{ field: 'cell_id' }],
            y: [{ field: 'duration_s', agg: 'sum' }],
        };
        const spec = buildXyQuery(values, CTX);
        expect(spec.groupBy).toEqual(['tariff', 'cell_id']);
        expect(spec.metrics[0]).toEqual({ id: 'sum_duration_s', expression: 'SUM("duration_s")', label: 'sum(duration_s)' });
    });
});

describe('transformXy', () => {
    it('pivots a single metric series keyed by x', () => {
        const rows = [
            { tariff: 'premium', sum_duration_s: 30 },
            { tariff: 'standard', sum_duration_s: 12 },
        ];
        const values: ControlValues = { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' }] };
        const props = transformXy(rows, values);
        expect(props.labels).toEqual(['premium', 'standard']);
        expect(props.series).toHaveLength(1);
        expect(props.series[0].data).toEqual([30, 12]);
    });

    it('pivots one dataset per series value, aligned to the x labels', () => {
        const rows = [
            { tariff: 'premium', cell_id: 'A', sum_duration_s: 30 },
            { tariff: 'standard', cell_id: 'A', sum_duration_s: 12 },
            { tariff: 'premium', cell_id: 'B', sum_duration_s: 7 },
        ];
        const values: ControlValues = {
            x: [{ field: 'tariff' }],
            series: [{ field: 'cell_id' }],
            y: [{ field: 'duration_s', agg: 'sum' }],
        };
        const props = transformXy(rows, values);
        expect(props.labels).toEqual(['premium', 'standard']);
        const byLabel = Object.fromEntries(props.series.map((s) => [s.label, s.data]));
        expect(byLabel['A']).toEqual([30, 12]);
        expect(byLabel['B']).toEqual([7, 0]); // missing standard/B → 0
    });
});

describe('value (KPI) helpers', () => {
    it('builds an ungrouped single-metric query', () => {
        const values: ControlValues = { value: [{ field: 'duration_s', agg: 'sum' }] };
        const spec = buildValueQuery(values, CTX);
        expect(spec.groupBy).toEqual([]);
        expect(spec.metrics).toHaveLength(1);
    });

    it('reads the headline value from the first result row', () => {
        const props = transformValue([{ sum_duration_s: 1234 }], { value: [{ field: 'duration_s', agg: 'sum' }] });
        expect(props.value).toBe(1234);
    });
});
