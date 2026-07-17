import { describe, expect, it } from 'vitest';
import { getKind } from 'app/inspecto/component-model';
import { allViz } from 'app/inspecto/viz';
import { WIDGET_KIND, validateWidgetConfig } from './widget.kind';

// No defensive re-seed needed: specs that empty the shared per-worker registries do so via the
// isolate*/restore contract, so this file always sees the module-load registrations intact.
describe('widget ComponentKind', () => {
    it('registers on the model with a mapping wiring and dataset as a part kind', () => {
        expect(getKind('widget')).toBe(WIDGET_KIND);
        expect(WIDGET_KIND.wiring).toBe('mapping');
        expect(WIDGET_KIND.allowedPartKinds).toContain('dataset');
    });

    it('registers the built-in viz plugins as a side effect', () => {
        expect(allViz().map((p) => p.meta.type)).toEqual(expect.arrayContaining(['bar', 'line', 'kpi', 'table']));
    });

    it('derives a channel mapping wiring from the controls', () => {
        const wiring = WIDGET_KIND.deriveWiring!([], {
            datasetId: 'cdr',
            vizType: 'bar',
            controls: { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' }] },
        });
        expect(wiring).toEqual({ strategy: 'mapping', channels: { x: 'tariff', y: 'duration_s' } });
    });

    it('flags a widget with no dataset or viz type', () => {
        const findings = validateWidgetConfig({ controls: {} });
        expect(findings.some((f) => f.path === 'datasetId')).toBe(true);
        expect(findings.some((f) => f.path === 'vizType')).toBe(true);
    });

    it('view-bound widget: requires a saved view instead of a dataset', () => {
        const missing = validateWidgetConfig({ vizType: 'geo-map', controls: {} });
        expect(missing.some((f) => f.path === 'viewId')).toBe(true);
        expect(missing.some((f) => f.path === 'datasetId')).toBe(false);
        const ok = validateWidgetConfig({ vizType: 'link-analysis', controls: {}, viewId: 'graph-complex' });
        expect(ok).toEqual([]);
    });
});
