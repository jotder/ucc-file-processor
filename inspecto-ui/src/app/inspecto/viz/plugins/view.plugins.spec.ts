import { describe, expect, it } from 'vitest';
import { registerBuiltinViz } from './index';
import { GEO_MAP_PLUGIN, LINK_ANALYSIS_PLUGIN } from './view.plugins';
import { recommend } from '../show-me';
import { VizField } from '../viz-types';

describe('view-bound plugins (geo-map / link-analysis)', () => {
    it('declare their saved-view kind and an inert query pipeline', () => {
        expect(GEO_MAP_PLUGIN.meta.viewKind).toBe('geo-map-view');
        expect(LINK_ANALYSIS_PLUGIN.meta.viewKind).toBe('link-analysis-view');
        expect(GEO_MAP_PLUGIN.render).toEqual({ kind: 'component', componentKey: 'geo-map-view' });
        expect(LINK_ANALYSIS_PLUGIN.render).toEqual({ kind: 'component', componentKey: 'link-analysis-view' });
        expect(GEO_MAP_PLUGIN.controls).toEqual([]);
        const spec = GEO_MAP_PLUGIN.buildQuery({}, { datasetId: 'x', sourceName: 'y', filters: null });
        expect(spec.groupBy).toEqual([]);
        expect(spec.measures).toEqual([]);
        expect(GEO_MAP_PLUGIN.transformProps([], {})).toEqual({ labels: [], series: [] });
    });

    it('are excluded from Show-Me recommendations (no field set can recommend a saved view)', () => {
        registerBuiltinViz(); // guarded — order-independent under the shared per-worker registry
        const fields: VizField[] = [
            { name: 'tariff', type: 'string', role: 'dimension' },
            { name: 'cost_usd', type: 'number', role: 'measure' },
        ];
        const types = recommend(fields).map((p) => p.meta.type);
        expect(types).toContain('bar');
        expect(types).not.toContain('geo-map');
        expect(types).not.toContain('link-analysis');
    });
});
