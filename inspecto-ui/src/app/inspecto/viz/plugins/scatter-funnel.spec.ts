import { describe, expect, it } from 'vitest';
import { ControlValues } from '../viz-types';
import { SCATTER_PLUGIN } from './scatter.plugin';
import { FUNNEL_PLUGIN } from './funnel.plugin';

const CTX = { datasetId: 'd', sourceName: 'cdr' };

describe('SCATTER_PLUGIN', () => {
    const values: ControlValues = {
        series: [{ field: 'tariff' }],
        x: [{ field: 'duration_s', agg: 'avg' }],
        y: [{ field: 'cost_usd', agg: 'avg' }],
    };

    it('groups by the label dimension with two avg measures', () => {
        const spec = SCATTER_PLUGIN.buildQuery(values, CTX);
        expect(spec.groupBy).toEqual(['tariff']);
        expect(spec.measures.map((m) => m.id)).toEqual(['avg_duration_s', 'avg_cost_usd']);
    });

    it('transforms rows into parallel x/y series with group labels', () => {
        const rows = [
            { tariff: 'premium', avg_duration_s: 373, avg_cost_usd: 2.1 },
            { tariff: 'standard', avg_duration_s: 28, avg_cost_usd: 0.2 },
        ];
        const props = SCATTER_PLUGIN.transformProps(rows, values);
        expect(props.labels).toEqual(['premium', 'standard']);
        expect(props.series[0].data).toEqual([373, 28]);
        expect(props.series[1].data).toEqual([2.1, 0.2]);
    });
});

describe('FUNNEL_PLUGIN', () => {
    const values: ControlValues = { x: [{ field: 'stage' }], y: [{ field: 'n', agg: 'sum' }] };

    it('sorts stages largest-first', () => {
        const rows = [
            { stage: 'won', sum_n: 5 },
            { stage: 'visited', sum_n: 100 },
            { stage: 'signed_up', sum_n: 40 },
        ];
        const props = FUNNEL_PLUGIN.transformProps(rows, values);
        expect(props.labels).toEqual(['visited', 'signed_up', 'won']);
        expect(props.series[0].data).toEqual([100, 40, 5]);
    });

    it('is unfit for temporal-only data (Show-Me hint)', () => {
        expect(FUNNEL_PLUGIN.meta.fit.temporal).toBe(false);
        expect(FUNNEL_PLUGIN.meta.fit.maxCardinality).toBe(10);
    });
});
