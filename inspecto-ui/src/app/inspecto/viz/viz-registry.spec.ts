import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { allViz, clearViz, getViz, registerViz, restoreViz, snapshotViz } from './viz-registry';
import { VizPlugin } from './viz-types';

function fakePlugin(type: string): VizPlugin {
    return {
        meta: { type, label: type, icon: 'x', fit: {} },
        controls: [],
        buildQuery: (_v, ctx) => ({ datasetId: ctx.datasetId, sourceName: ctx.sourceName, groupBy: [], measures: [] }),
        transformProps: () => ({ labels: [], series: [] }),
        render: { kind: 'aggrid' },
    };
}

describe('viz-registry', () => {
    let saved: VizPlugin[];
    // Snapshot then start empty; restore afterwards so builtins registered in the shared (per-worker)
    // registry survive for other specs (e.g. result-set.spec's Show-Me recommender check).
    beforeEach(() => {
        saved = snapshotViz();
        clearViz();
    });
    afterEach(() => restoreViz(saved));

    it('registers and retrieves plugins', () => {
        registerViz(fakePlugin('line'));
        expect(getViz('line')?.meta.type).toBe('line');
        expect(allViz()).toHaveLength(1);
    });

    it('throws on a duplicate type', () => {
        registerViz(fakePlugin('bar'));
        expect(() => registerViz(fakePlugin('bar'))).toThrow(/Duplicate VizPlugin/);
    });
});
