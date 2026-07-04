import { describe, expect, it } from 'vitest';
import type { G6GraphData } from './graph-types';
import {
    ANALYSIS_NODE_CAP,
    allPaths,
    betweennessCentrality,
    collapseBranches,
    connectedComponents,
    degreeCentrality,
    descendants,
    detectCommunities,
    explainNode,
    filterByKinds,
    neighborhood,
    searchNodes,
    shortestPath,
} from './graph-analysis';

const node = (id: string, kind = 'entity', label = id): G6GraphData['nodes'][0] => ({ id, data: { label, kind } });
const edge = (source: string, target: string, kind = 'link'): G6GraphData['edges'][0] => ({
    id: `${source}->${target}:${kind}`,
    source,
    target,
    data: { kind },
});

/** a→b→c→d with a shortcut a→c, plus an isolated island x→y and a cycle back d→a. */
const g: G6GraphData = {
    nodes: ['a', 'b', 'c', 'd', 'x', 'y'].map((id) => node(id)),
    edges: [edge('a', 'b'), edge('b', 'c'), edge('a', 'c'), edge('c', 'd'), edge('d', 'a'), edge('x', 'y')],
};

describe('shortestPath', () => {
    it('finds the shortest route, not the longer one', () => {
        const p = shortestPath(g, 'a', 'd', 'out')!;
        expect(p.nodeIds).toEqual(['a', 'c', 'd']);
        expect(p.edgeIds).toEqual(['a->c:link', 'c->d:link']);
    });

    it('respects direction', () => {
        expect(shortestPath(g, 'b', 'a', 'out')!.nodeIds).toEqual(['b', 'c', 'd', 'a']); // via the cycle
        expect(shortestPath(g, 'b', 'a', 'in')!.nodeIds).toEqual(['b', 'a']);
    });

    it('returns null when disconnected and a trivial path for self', () => {
        expect(shortestPath(g, 'a', 'x')).toBeNull();
        expect(shortestPath(g, 'a', 'missing')).toBeNull();
        expect(shortestPath(g, 'a', 'a')).toEqual({ nodeIds: ['a'], edgeIds: [] });
    });
});

describe('allPaths', () => {
    it('finds every simple path within the limit (cycle does not loop forever)', () => {
        const paths = allPaths(g, 'a', 'd', { direction: 'out' });
        expect(paths.map((p) => p.nodeIds)).toEqual(
            expect.arrayContaining([
                ['a', 'c', 'd'],
                ['a', 'b', 'c', 'd'],
            ]),
        );
        expect(paths).toHaveLength(2);
    });

    it('honors the path limit', () => {
        expect(allPaths(g, 'a', 'd', { direction: 'out', limit: 1 })).toHaveLength(1);
    });
});

describe('neighborhood', () => {
    it('keeps only the N-hop ball plus interior edges', () => {
        const nb = neighborhood(g, 'b', 1);
        expect(nb.nodes.map((n) => n.id).sort()).toEqual(['a', 'b', 'c']);
        expect(nb.edges.map((e) => e.id).sort()).toEqual(['a->b:link', 'a->c:link', 'b->c:link']);
    });

    it('2 hops reaches the whole cycle but never the island', () => {
        const nb = neighborhood(g, 'a', 2);
        expect(nb.nodes.map((n) => n.id).sort()).toEqual(['a', 'b', 'c', 'd']);
    });
});

describe('explainNode', () => {
    it('summarizes links grouped by kind and direction', () => {
        const s = explainNode(g, 'a');
        expect(s).toContain('a (entity)');
        expect(s).toContain('→ link: b, c');
        expect(s).toContain('← link: d');
    });

    it('handles unknown and isolated nodes', () => {
        expect(explainNode(g, 'nope')).toBe('Unknown node.');
        const lonely: G6GraphData = { nodes: [node('solo')], edges: [] };
        expect(explainNode(lonely, 'solo')).toContain('no links');
    });
});

describe('centrality', () => {
    it('degree ranks the hub first', () => {
        const scores = degreeCentrality(g);
        expect(scores[0].id).toBe('a'); // a and c both have degree 3; the label tiebreak puts a first
        expect(scores[0].score).toBe(3);
    });

    it('betweenness ranks the bridge node highest', () => {
        // path graph p-q-r: q carries all shortest paths
        const line: G6GraphData = { nodes: [node('p'), node('q'), node('r')], edges: [edge('p', 'q'), edge('q', 'r')] };
        const scores = betweennessCentrality(line);
        expect(scores[0].id).toBe('q');
        expect(scores[0].score).toBe(1);
    });

    it('betweenness refuses graphs above the cap', () => {
        const big: G6GraphData = {
            nodes: Array.from({ length: ANALYSIS_NODE_CAP + 1 }, (_, i) => node(`n${i}`)),
            edges: [],
        };
        expect(() => betweennessCentrality(big)).toThrow(/capped/);
    });
});

describe('detectCommunities', () => {
    it('separates two dense clusters joined by one bridge', () => {
        const clusterEdges = (ids: string[]): G6GraphData['edges'] =>
            ids.flatMap((s, i) => ids.slice(i + 1).map((t) => edge(s, t)));
        const left = ['l1', 'l2', 'l3'];
        const right = ['r1', 'r2', 'r3'];
        const two: G6GraphData = {
            nodes: [...left, ...right].map((id) => node(id)),
            edges: [...clusterEdges(left), ...clusterEdges(right), edge('l1', 'r1')],
        };
        const c = detectCommunities(two);
        expect(c.get('l2')).toBe(c.get('l3'));
        expect(c.get('r2')).toBe(c.get('r3'));
        expect(c.get('l2')).not.toBe(c.get('r2'));
    });

    it('is deterministic', () => {
        const a = detectCommunities(g);
        const b = detectCommunities(g);
        expect([...a.entries()]).toEqual([...b.entries()]);
    });
});

describe('connectedComponents', () => {
    it('finds both components, largest first', () => {
        const comps = connectedComponents(g);
        expect(comps).toHaveLength(2);
        expect(comps[0].sort()).toEqual(['a', 'b', 'c', 'd']);
        expect(comps[1].sort()).toEqual(['x', 'y']);
    });
});

describe('searchNodes / filterByKinds', () => {
    const typed: G6GraphData = {
        nodes: [node('acc1', 'account', 'Account 1'), node('dev1', 'device', 'Device 1'), node('acc2', 'account', 'Account 2')],
        edges: [edge('acc1', 'dev1', 'uses'), edge('acc2', 'dev1', 'uses'), edge('acc1', 'acc2', 'calls')],
    };

    it('searches label and id case-insensitively; blank matches nothing', () => {
        expect(searchNodes(typed, 'account')).toEqual(['acc1', 'acc2']);
        expect(searchNodes(typed, 'DEV')).toEqual(['dev1']);
        expect(searchNodes(typed, '  ')).toEqual([]);
    });

    it('filters nodes by kind and drops edges with a lost endpoint', () => {
        const f = filterByKinds(typed, ['account'], []);
        expect(f.nodes.map((n) => n.id)).toEqual(['acc1', 'acc2']);
        expect(f.edges.map((e) => e.data.kind)).toEqual(['calls']);
    });

    it('filters edges by kind; empty filters are a no-op', () => {
        expect(filterByKinds(typed, [], ['uses']).edges).toHaveLength(2);
        expect(filterByKinds(typed, [], [])).toEqual(typed);
    });
});

describe('descendants / collapseBranches', () => {
    /** A tree r→(b1,b2); b1→(l1,l2); b2→l3 — plus the island x→y. */
    const tree: G6GraphData = {
        nodes: ['r', 'b1', 'b2', 'l1', 'l2', 'l3', 'x', 'y'].map((id) => node(id)),
        edges: [edge('r', 'b1'), edge('r', 'b2'), edge('b1', 'l1'), edge('b1', 'l2'), edge('b2', 'l3'), edge('x', 'y')],
    };

    it('descendants walks outgoing edges only, excluding the root', () => {
        expect([...descendants(tree, 'b1')].sort()).toEqual(['l1', 'l2']);
        expect([...descendants(tree, 'r')].sort()).toEqual(['b1', 'b2', 'l1', 'l2', 'l3']);
        expect(descendants(tree, 'l1').size).toBe(0);
        expect([...descendants(g, 'a')].sort()).toEqual(['b', 'c', 'd']); // survives the d→a cycle without looping
    });

    it('collapseBranches hides each root’s subtree but keeps the roots visible', () => {
        const c = collapseBranches(tree, ['b1']);
        expect(c.nodes.map((n) => n.id).sort()).toEqual(['b1', 'b2', 'l3', 'r', 'x', 'y']);
        expect(c.edges.some((e) => e.source === 'b1')).toBe(false); // edges into the hidden subtree drop
        expect(collapseBranches(tree, []).nodes).toHaveLength(8); // no-op without roots
        // collapsing an ancestor keeps a collapsed descendant root visible too
        expect(collapseBranches(tree, ['r', 'b1']).nodes.map((n) => n.id).sort()).toEqual(['b1', 'r', 'x', 'y']);
    });
});
