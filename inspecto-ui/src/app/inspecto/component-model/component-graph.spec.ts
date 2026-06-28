import { describe, expect, it } from 'vitest';
import { deriveComponentGraph } from './component-graph';
import { Component } from './component-types';

const atom = (kind: string, id: string): Component => ({ kind, id, name: id, config: {} });

describe('deriveComponentGraph', () => {
    it('emits a node per component and no edges for atomic components', () => {
        const g = deriveComponentGraph({ components: [atom('dataset', 'd1'), atom('grammar', 'g1')] });
        expect(g.nodes.map((n) => n.id).sort()).toEqual(['dataset/d1', 'grammar/g1']);
        expect(g.edges).toHaveLength(0);
    });

    it('emits a parent→part "uses" edge for a composite', () => {
        const chart: Component = {
            kind: 'chart',
            id: 'c1',
            name: 'c1',
            config: {},
            parts: [{ partId: 'p1', ref: { kind: 'dataset', id: 'd1' } }],
        };
        const g = deriveComponentGraph({ components: [chart, atom('dataset', 'd1')] });
        expect(g.edges).toHaveLength(1);
        expect(g.edges[0]).toMatchObject({ source: 'chart/c1', target: 'dataset/d1', data: { kind: 'uses' } });
        expect(g.nodes.find((n) => n.id === 'dataset/d1')?.data.missing).toBeUndefined();
    });

    it('creates a ghost node for a dangling reference', () => {
        const chart: Component = {
            kind: 'chart',
            id: 'c1',
            name: 'c1',
            config: {},
            parts: [{ partId: 'p1', ref: { kind: 'dataset', id: 'missing' } }],
        };
        const g = deriveComponentGraph({ components: [chart] });
        expect(g.nodes.find((n) => n.id === 'dataset/missing')?.data.missing).toBe(true);
        expect(g.edges).toHaveLength(1);
    });

    it('inlines an embedded part as its own node', () => {
        const chart: Component = {
            kind: 'chart',
            id: 'c1',
            name: 'c1',
            config: {},
            parts: [
                {
                    partId: 'p1',
                    ref: { kind: 'dataset', inline: { kind: 'dataset', id: 'inl', name: 'Inline DS', config: {} } },
                },
            ],
        };
        const g = deriveComponentGraph({ components: [chart] });
        expect(g.nodes.find((n) => n.id === 'dataset/c1::p1')?.data.label).toBe('Inline DS');
        expect(g.edges[0].target).toBe('dataset/c1::p1');
    });
});
