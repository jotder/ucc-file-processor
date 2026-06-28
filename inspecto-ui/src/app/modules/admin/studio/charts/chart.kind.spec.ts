import { describe, expect, it } from 'vitest';
import { getKind } from 'app/inspecto/component-model';
import { allViz } from 'app/inspecto/viz';
import { CHART_KIND, validateChartConfig } from './chart.kind';

describe('chart ComponentKind', () => {
    it('registers on the model with a mapping wiring and dataset as a part kind', () => {
        expect(getKind('chart')).toBe(CHART_KIND);
        expect(CHART_KIND.wiring).toBe('mapping');
        expect(CHART_KIND.allowedPartKinds).toContain('dataset');
    });

    it('registers the built-in viz plugins as a side effect', () => {
        expect(allViz().map((p) => p.meta.type)).toEqual(expect.arrayContaining(['bar', 'line', 'kpi', 'table']));
    });

    it('derives a channel mapping wiring from the controls', () => {
        const wiring = CHART_KIND.deriveWiring!([], {
            datasetId: 'cdr',
            vizType: 'bar',
            controls: { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' }] },
        });
        expect(wiring).toEqual({ strategy: 'mapping', channels: { x: 'tariff', y: 'duration_s' } });
    });

    it('flags a chart with no dataset or viz type', () => {
        const findings = validateChartConfig({ controls: {} });
        expect(findings.some((f) => f.path === 'datasetId')).toBe(true);
        expect(findings.some((f) => f.path === 'vizType')).toBe(true);
    });
});
