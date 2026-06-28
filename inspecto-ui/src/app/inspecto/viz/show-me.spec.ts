import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { clearViz, registerViz } from './viz-registry';
import { recommend, autoAssignChannels } from './show-me';
import { ControlSpec, VizField, VizPlugin } from './viz-types';

function plugin(type: string, fit: VizPlugin['meta']['fit'], controls: ControlSpec[]): VizPlugin {
    return {
        meta: { type, label: type, icon: 'x', fit },
        controls,
        buildQuery: (_v, ctx) => ({ datasetId: ctx.datasetId, sourceName: ctx.sourceName, groupBy: [], metrics: [] }),
        transformProps: () => ({ labels: [], series: [] }),
        render: { kind: 'chartjs', chartType: type },
    };
}

const LINE = plugin('line', { minMetric: 1, temporal: true }, [
    { channel: 'x', label: 'Time', acceptRoles: ['temporal', 'dimension'] },
    { channel: 'y', label: 'Measure', acceptRoles: ['metric'], isMetric: true },
]);
const BAR = plugin('bar', { minMetric: 1, minDim: 1 }, [
    { channel: 'x', label: 'Category', acceptRoles: ['dimension'] },
    { channel: 'y', label: 'Measure', acceptRoles: ['metric'], isMetric: true },
]);
const TABLE = plugin('table', {}, []);

const TEMPORAL_FIELDS: VizField[] = [
    { name: 'event_time', type: 'date', role: 'temporal' },
    { name: 'duration_s', type: 'number', role: 'metric' },
];
const CATEGORICAL_FIELDS: VizField[] = [
    { name: 'tariff', type: 'string', role: 'dimension' },
    { name: 'duration_s', type: 'number', role: 'metric' },
];

describe('recommend', () => {
    beforeEach(() => {
        registerViz(LINE);
        registerViz(BAR);
        registerViz(TABLE);
    });
    afterEach(() => clearViz());

    it('ranks line first when a temporal field is present', () => {
        const ranked = recommend(TEMPORAL_FIELDS).map((p) => p.meta.type);
        expect(ranked[0]).toBe('line');
        expect(ranked).toContain('table');
    });

    it('disqualifies line (needs temporal) for purely categorical fields', () => {
        const ranked = recommend(CATEGORICAL_FIELDS).map((p) => p.meta.type);
        expect(ranked).not.toContain('line');
        expect(ranked).toContain('bar');
    });
});

describe('autoAssignChannels', () => {
    afterEach(() => clearViz());

    it('maps temporal→x and metric→y (with a default agg)', () => {
        const values = autoAssignChannels(LINE, TEMPORAL_FIELDS);
        expect(values.x?.[0].field).toBe('event_time');
        expect(values.y?.[0]).toEqual({ field: 'duration_s', agg: 'sum' });
    });

    it('maps dimension→x when there is no temporal field', () => {
        const values = autoAssignChannels(BAR, CATEGORICAL_FIELDS);
        expect(values.x?.[0].field).toBe('tariff');
        expect(values.y?.[0].field).toBe('duration_s');
    });
});
