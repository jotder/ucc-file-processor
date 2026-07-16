import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { allViz, getViz, isolateViz, registerViz } from './viz-registry';
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
    let restoreViz: () => void;
    beforeEach(() => (restoreViz = isolateViz())); // start empty, and put the shared (per-worker) registry back after
    afterEach(() => restoreViz());

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
