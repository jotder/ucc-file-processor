import { describe, expect, it } from 'vitest';
import { channelMeasure, channelMeasureId } from './query-spec';

describe('channelMeasure / channelMeasureId', () => {
    it('compiles a plain column channel to agg(field)', () => {
        expect(channelMeasure({ field: 'duration_s', agg: 'avg' })).toEqual({
            id: 'avg_duration_s',
            expression: 'AVG("duration_s")',
            label: 'avg(duration_s)',
        });
        expect(channelMeasureId({ field: 'duration_s', agg: 'avg' })).toBe('avg_duration_s');
    });

    it('defaults to sum when no aggregation is set', () => {
        expect(channelMeasureId({ field: 'cost_usd' })).toBe('sum_cost_usd');
    });

    it('uses a named measure expression verbatim with an identifier-safe id', () => {
        const cv = { field: 'avg-rate', expression: 'sum(duration_s) / count(*)' };
        expect(channelMeasure(cv)).toEqual({
            id: 'avg_rate',
            expression: 'sum(duration_s) / count(*)',
            label: 'avg-rate',
        });
        expect(channelMeasureId(cv)).toBe('avg_rate');
    });
});
