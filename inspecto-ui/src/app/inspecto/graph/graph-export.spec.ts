import { describe, expect, it } from 'vitest';
import type { G6GraphData } from './graph-types';
import { toGraphml, toSvg } from './graph-export';

const G: G6GraphData = {
    nodes: [
        { id: 'a', data: { label: 'A"lice', kind: 'entity' } },
        { id: 'b', data: { label: 'Bob', kind: 'entity' } },
    ],
    edges: [{ id: 'a->b', source: 'a', target: 'b', data: { kind: 'calls' } }],
};

describe('toSvg', () => {
    it('renders one circle per positioned node, one line per edge with both endpoints positioned, and escapes labels', () => {
        const positions = new Map([['a', { x: 0, y: 0 }], ['b', { x: 100, y: 50 }]]);
        const svg = toSvg(G, positions);
        expect(svg).toContain('<svg');
        expect((svg.match(/<circle/g) ?? [])).toHaveLength(2);
        expect((svg.match(/<line/g) ?? [])).toHaveLength(1);
        expect(svg).toContain('A&quot;lice');
        expect(svg).not.toContain('A"lice');
    });

    it('skips a node/edge with no known position rather than throwing', () => {
        const positions = new Map([['a', { x: 0, y: 0 }]]); // 'b' unpositioned
        const svg = toSvg(G, positions);
        expect((svg.match(/<circle/g) ?? [])).toHaveLength(1);
        expect((svg.match(/<line/g) ?? [])).toHaveLength(0);
    });

    it('produces a valid non-degenerate viewBox for an empty position map', () => {
        const svg = toSvg(G, new Map());
        expect(svg).toMatch(/viewBox="0 0 \d+ \d+"/);
    });
});

describe('toGraphml', () => {
    it('emits generic GraphML (no vendor dialect) with one node/edge per element, escaped', () => {
        const xml = toGraphml(G);
        expect(xml).toContain('<graphml xmlns="http://graphml.graphdrawing.org/xmlns">');
        expect((xml.match(/<node /g) ?? [])).toHaveLength(2);
        expect((xml.match(/<edge /g) ?? [])).toHaveLength(1);
        expect(xml).toContain('A&quot;lice');
        expect(xml).toContain('source="a" target="b"');
        expect(xml).not.toMatch(/xmlns:y=/); // no yEd dialect
    });
});
