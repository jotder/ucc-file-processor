import { describe, expect, it } from 'vitest';
import type { G6GraphData } from './graph-types';
import {
    ANALYSIS_NODE_CAP,
    allPaths,
    articulationPoints,
    betweennessCentrality,
    bridges,
    cliques,
    closenessCentrality,
    collapseBranches,
    connectedComponents,
    degreeCentrality,
    descendants,
    detectCommunities,
    edgeWeight,
    egoNetwork,
    eigenvectorCentrality,
    explainNode,
    filterByKinds,
    filterByTime,
    findCycles,
    hits,
    isForest,
    jaccardSimilarity,
    kCore,
    katzCentrality,
    linkPrediction,
    louvainCommunities,
    matchPattern,
    maxFlow,
    maximumSpanningForest,
    neighborhood,
    pageRank,
    searchNodes,
    shortestPath,
    suspicionScore,
    triangleCount,
    weightedShortestPath,
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

describe('filterByTime', () => {
    const timed: G6GraphData = {
        nodes: [node('a'), node('b'), node('c')],
        edges: [
            { ...edge('a', 'b', 'calls'), data: { kind: 'calls', attrs: { when: '2026-01-01T00:00:00Z' } } },
            { ...edge('b', 'c', 'calls'), data: { kind: 'calls', attrs: { when: '2026-06-01T00:00:00Z' } } },
            { ...edge('a', 'c', 'calls'), data: { kind: 'calls', attrs: { when: 'not-a-date' } } },
            { ...edge('c', 'a', 'calls'), data: { kind: 'calls' } }, // no attrs at all
        ],
    };

    it('keeps only edges whose attr column parses to a date on or before the cutoff', () => {
        const cutoff = Date.parse('2026-03-01T00:00:00Z');
        const f = filterByTime(timed, 'when', cutoff);
        expect(f.nodes).toEqual(timed.nodes); // nodes untouched
        expect(f.edges.map((e) => e.id)).toEqual([edge('a', 'b', 'calls').id]);
    });

    it('drops edges missing the column or with an unparseable value', () => {
        const cutoff = Date.parse('2027-01-01T00:00:00Z');
        const f = filterByTime(timed, 'when', cutoff);
        expect(f.edges.map((e) => e.id)).toEqual([edge('a', 'b', 'calls').id, edge('b', 'c', 'calls').id]);
    });

    it('an empty attrCol is a no-op', () => {
        expect(filterByTime(timed, '', Date.now())).toEqual(timed);
    });
});

describe('isForest', () => {
    /** A tree r→(b1,b2); b1→(l1,l2); b2→l3 — plus the island x→y. A forest (two trees). */
    const forest: G6GraphData = {
        nodes: ['r', 'b1', 'b2', 'l1', 'l2', 'l3', 'x', 'y'].map((id) => node(id)),
        edges: [edge('r', 'b1'), edge('r', 'b2'), edge('b1', 'l1'), edge('b1', 'l2'), edge('b2', 'l3'), edge('x', 'y')],
    };

    it('accepts a forest of trees', () => {
        expect(isForest(forest)).toBe(true);
    });

    it('rejects a node with two parents', () => {
        const diamond: G6GraphData = { nodes: ['a', 'b', 'c', 'd'].map((id) => node(id)), edges: [edge('a', 'b'), edge('a', 'c'), edge('b', 'd'), edge('c', 'd')] };
        expect(isForest(diamond)).toBe(false); // d has two parents
    });

    it('rejects a cycle even when every node has one parent', () => {
        expect(isForest(g)).toBe(false); // the d→a cycle
        const pureCycle: G6GraphData = { nodes: ['p', 'q', 'r'].map((id) => node(id)), edges: [edge('p', 'q'), edge('q', 'r'), edge('r', 'p')] };
        expect(isForest(pureCycle)).toBe(false);
    });

    it('rejects the empty graph', () => {
        expect(isForest({ nodes: [], edges: [] })).toBe(false);
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

describe('louvainCommunities', () => {
    /** All undirected pairs of a node set — a clique. */
    const clique = (ids: string[]): G6GraphData['edges'] =>
        ids.flatMap((s, i) => ids.slice(i + 1).map((t) => edge(s, t)));

    it('separates two cliques joined by a single bridge, labelling each by its smallest member', () => {
        const twoCliques: G6GraphData = {
            nodes: ['a1', 'a2', 'a3', 'a4', 'b1', 'b2', 'b3', 'b4'].map((id) => node(id)),
            edges: [...clique(['a1', 'a2', 'a3', 'a4']), ...clique(['b1', 'b2', 'b3', 'b4']), edge('a1', 'b1')],
        };
        const c = louvainCommunities(twoCliques);
        expect(['a1', 'a2', 'a3', 'a4'].every((id) => c.get(id) === 'a1')).toBe(true);
        expect(['b1', 'b2', 'b3', 'b4'].every((id) => c.get(id) === 'b1')).toBe(true);
        expect(c.get('a1')).not.toBe(c.get('b1'));
    });

    it('keeps a single dense component as one community', () => {
        const tri: G6GraphData = { nodes: ['p', 'q', 'r'].map((id) => node(id)), edges: [edge('p', 'q'), edge('q', 'r'), edge('r', 'p')] };
        const c = louvainCommunities(tri);
        expect(new Set(c.values()).size).toBe(1);
        expect(c.get('r')).toBe('p'); // smallest-member label
    });

    it('returns singletons when there are no edges, and is capped', () => {
        expect(louvainCommunities({ nodes: ['m', 'n'].map((id) => node(id)), edges: [] })).toEqual(new Map([['m', 'm'], ['n', 'n']]));
        const big: G6GraphData = { nodes: Array.from({ length: ANALYSIS_NODE_CAP + 1 }, (_, i) => node(`n${i}`)), edges: [] };
        expect(() => louvainCommunities(big)).toThrow(/capped/);
    });
});

describe('matchPattern', () => {
    /** acc1 —transfer→ acc2 —transfer→ m1, plus acc1 —pay→ acc3. */
    const fraud: G6GraphData = {
        nodes: [node('acc1', 'account'), node('acc2', 'account'), node('m1', 'merchant'), node('acc3', 'account')],
        edges: [edge('acc1', 'acc2', 'transfer'), edge('acc2', 'm1', 'transfer'), edge('acc1', 'acc3', 'pay')],
    };

    it('matches an ordered node/edge-kind path motif with its node+edge ids', () => {
        const m = matchPattern(fraud, [{ nodeKind: 'account' }, { edgeKind: 'transfer', nodeKind: 'account' }, { edgeKind: 'transfer', nodeKind: 'merchant' }]);
        expect(m).toHaveLength(1);
        expect(m[0].nodeIds).toEqual(['acc1', 'acc2', 'm1']);
        expect(m[0].edgeIds).toEqual(['acc1->acc2:transfer', 'acc2->m1:transfer']);
    });

    it('treats absent kinds as wildcards and follows the requested direction', () => {
        expect(matchPattern(fraud, [{}, {}])).toHaveLength(3); // every out-edge = a 2-node path
        expect(matchPattern(fraud, [{ nodeKind: 'merchant' }, { nodeKind: 'account', edgeKind: 'transfer', direction: 'in' }])[0].nodeIds).toEqual(['m1', 'acc2']);
    });

    it('returns no matches when the motif does not occur, and strips the folded-count edge suffix', () => {
        expect(matchPattern(fraud, [{ nodeKind: 'account' }, { edgeKind: 'pay', nodeKind: 'merchant' }])).toHaveLength(0);
        const folded: G6GraphData = { nodes: [node('p', 'account'), node('q', 'account')], edges: [edge('p', 'q', 'transfer · 2')] };
        expect(matchPattern(folded, [{ nodeKind: 'account' }, { edgeKind: 'transfer', nodeKind: 'account' }])).toHaveLength(1);
    });

    it('caps the number of matches', () => {
        const star: G6GraphData = { nodes: ['c', 'm0', 'm1', 'm2'].map((id) => node(id)), edges: ['m0', 'm1', 'm2'].map((t) => edge('c', t)) };
        expect(matchPattern(star, [{}, {}], { limit: 2 })).toHaveLength(2);
    });
});

// ── V2: advanced traversal ──

describe('edgeWeight', () => {
    it('reads the folded-count suffix, the runtime count, or defaults to 1', () => {
        expect(edgeWeight(edge('a', 'b'))).toBe(1);
        expect(edgeWeight(edge('a', 'b', 'calls · 3'))).toBe(3);
        expect(edgeWeight({ id: 'e', source: 'a', target: 'b', data: { kind: 'calls', count: 4 } as never })).toBe(4);
    });
});

describe('weightedShortestPath', () => {
    /** Direct a→c (weight 1) vs. the stronger two-hop a→b→c (weight 5 each). */
    const wg: G6GraphData = {
        nodes: ['a', 'b', 'c'].map((id) => node(id)),
        edges: [edge('a', 'c', 'link'), edge('a', 'b', 'link · 5'), edge('b', 'c', 'link · 5')],
    };

    it('prefers the strongest-tie route over the fewest-hops route', () => {
        expect(weightedShortestPathIds(wg, 'a', 'c')).toEqual(['a', 'b', 'c']);
        expect(shortestPath(wg, 'a', 'c', 'out')!.nodeIds).toEqual(['a', 'c']); // fewest hops disagrees
    });

    it('returns null when disconnected and trivial for self', () => {
        expect(weightedShortestPath(wg, 'a', 'zzz')).toBeNull();
        expect(weightedShortestPath(wg, 'a', 'a')).toEqual({ nodeIds: ['a'], edgeIds: [] });
    });

    function weightedShortestPathIds(gr: G6GraphData, from: string, to: string): string[] | null {
        return weightedShortestPath(gr, from, to, 'out')?.nodeIds ?? null;
    }
});

describe('findCycles', () => {
    it('finds each directed cycle once (canonicalized to its smallest member)', () => {
        const cycles = findCycles(g); // a→b→c→d→a and a→c→d→a
        expect(cycles).toHaveLength(2);
        expect(cycles.every((c) => c.nodeIds.includes('a'))).toBe(true);
        expect(cycles.every((c) => c.edgeIds.length === c.nodeIds.length)).toBe(true);
    });

    it('detects self-loops and honors the limit', () => {
        const selfLoop: G6GraphData = { nodes: [node('s')], edges: [edge('s', 's', 'x')] };
        expect(findCycles(selfLoop)).toEqual([{ nodeIds: ['s'], edgeIds: ['s->s:x'] }]);
        expect(findCycles(g, { limit: 1 })).toHaveLength(1);
    });
});

describe('articulationPoints / bridges', () => {
    const line: G6GraphData = { nodes: ['p', 'q', 'r'].map((id) => node(id)), edges: [edge('p', 'q'), edge('q', 'r')] };
    const tri: G6GraphData = { nodes: ['p', 'q', 'r'].map((id) => node(id)), edges: [edge('p', 'q'), edge('q', 'r'), edge('r', 'p')] };

    it('finds the cut vertex and cut edges of a path', () => {
        expect(articulationPoints(line)).toEqual(['q']);
        expect(bridges(line)).toEqual(['p->q:link', 'q->r:link']);
    });

    it('a cycle has no cut points', () => {
        expect(articulationPoints(tri)).toEqual([]);
        expect(bridges(tri)).toEqual([]);
    });
});

describe('egoNetwork', () => {
    it('is the 1-hop induced subgraph including neighbor-to-neighbor edges', () => {
        const ego = egoNetwork(g, 'b');
        expect(ego.nodes.map((n) => n.id).sort()).toEqual(['a', 'b', 'c']);
        expect(ego.edges.map((e) => e.id).sort()).toEqual(['a->b:link', 'a->c:link', 'b->c:link']);
    });
});

// ── V2: algorithm library ──

describe('pageRank', () => {
    it('ranks the well-cited sink highest and stays a distribution', () => {
        const star: G6GraphData = { nodes: ['hub', 'm0', 'm1', 'm2'].map((id) => node(id)), edges: ['m0', 'm1', 'm2'].map((s) => edge(s, 'hub')) };
        const pr = pageRank(star);
        expect(pr[0].id).toBe('hub');
        expect(pr.reduce((s, x) => s + x.score, 0)).toBeCloseTo(1, 5);
    });
});

describe('closenessCentrality', () => {
    it('ranks the central node of a path highest', () => {
        const line: G6GraphData = { nodes: ['p', 'q', 'r'].map((id) => node(id)), edges: [edge('p', 'q'), edge('q', 'r')] };
        expect(closenessCentrality(line)[0].id).toBe('q');
    });
});

describe('eigenvector / katz centrality', () => {
    const star: G6GraphData = { nodes: ['c', 'l1', 'l2', 'l3'].map((id) => node(id)), edges: ['l1', 'l2', 'l3'].map((l) => edge('c', l)) };
    it('both rank the hub of a star highest', () => {
        expect(eigenvectorCentrality(star)[0].id).toBe('c');
        expect(katzCentrality(star)[0].id).toBe('c');
    });
});

describe('hits', () => {
    it('separates hubs from authorities', () => {
        const h: G6GraphData = { nodes: ['h', 'h2', 'a1', 'a2'].map((id) => node(id)), edges: [edge('h', 'a1'), edge('h', 'a2'), edge('h2', 'a1')] };
        const { hubs, authorities } = hits(h);
        expect(authorities[0].id).toBe('a1'); // two in-links
        expect(hubs[0].id).toBe('h'); // points at both authorities
    });
});

describe('kCore / triangleCount', () => {
    /** A 4-clique a1..a4 plus a pendant leaf p off a1. */
    const clique4 = ['a1', 'a2', 'a3', 'a4'];
    const cliqueEdges = (ids: string[]): G6GraphData['edges'] => ids.flatMap((s, i) => ids.slice(i + 1).map((t) => edge(s, t)));
    const withLeaf: G6GraphData = { nodes: [...clique4, 'p'].map((id) => node(id)), edges: [...cliqueEdges(clique4), edge('a1', 'p')] };

    it('kCore gives the clique core 3 and the leaf core 1', () => {
        const core = new Map(kCore(withLeaf).map((s) => [s.id, s.score]));
        expect(clique4.every((id) => core.get(id) === 3)).toBe(true);
        expect(core.get('p')).toBe(1);
    });

    it('triangleCount counts a triangle once per member', () => {
        const tri: G6GraphData = { nodes: ['p', 'q', 'r'].map((id) => node(id)), edges: [edge('p', 'q'), edge('q', 'r'), edge('r', 'p')] };
        expect(triangleCount(tri).every((s) => s.score === 1)).toBe(true);
    });
});

describe('cliques', () => {
    it('finds maximal cliques of at least the requested size, largest first', () => {
        const cliqueEdges = (ids: string[]): G6GraphData['edges'] => ids.flatMap((s, i) => ids.slice(i + 1).map((t) => edge(s, t)));
        const two: G6GraphData = {
            nodes: ['a1', 'a2', 'a3', 'a4', 'b1', 'b2', 'b3'].map((id) => node(id)),
            edges: [...cliqueEdges(['a1', 'a2', 'a3', 'a4']), ...cliqueEdges(['b1', 'b2', 'b3'])],
        };
        const found = cliques(two);
        expect(found[0]).toEqual(['a1', 'a2', 'a3', 'a4']);
        expect(found).toContainEqual(['b1', 'b2', 'b3']);
    });
});

describe('maxFlow', () => {
    it('computes flow and returns the saturated min-cut', () => {
        const net: G6GraphData = {
            nodes: ['s', 'a', 'b', 't'].map((id) => node(id)),
            edges: [edge('s', 'a', 'x · 2'), edge('a', 't', 'x · 2'), edge('s', 'b', 'x · 3'), edge('b', 't', 'x · 1')],
        };
        const { value, minCut } = maxFlow(net, 's', 't');
        expect(value).toBe(3);
        expect(minCut.edgeIds.length).toBeGreaterThan(0);
    });

    it('is empty for an absent or equal endpoint', () => {
        const net: G6GraphData = { nodes: [node('s'), node('t')], edges: [edge('s', 't')] };
        expect(maxFlow(net, 's', 's').value).toBe(0);
        expect(maxFlow(net, 's', 'zzz').value).toBe(0);
    });
});

describe('maximumSpanningForest', () => {
    it('keeps the strongest edges without forming a cycle', () => {
        const tri: G6GraphData = { nodes: ['p', 'q', 'r'].map((id) => node(id)), edges: [edge('p', 'q', 'w · 3'), edge('q', 'r', 'w · 2'), edge('r', 'p', 'w · 1')] };
        const msf = maximumSpanningForest(tri);
        expect(msf.edgeIds).toEqual(['p->q:w · 3', 'q->r:w · 2']); // the weakest r→p edge is dropped
    });
});

describe('jaccardSimilarity / linkPrediction', () => {
    /** a and b both link to c and d, but not to each other. */
    const shared: G6GraphData = { nodes: ['a', 'b', 'c', 'd'].map((id) => node(id)), edges: [edge('a', 'c'), edge('a', 'd'), edge('b', 'c'), edge('b', 'd')] };

    it('jaccard ranks the co-associated node highest', () => {
        const sim = jaccardSimilarity(shared, 'a');
        expect(sim[0].id).toBe('b');
        expect(sim[0].score).toBe(1); // identical neighbor sets
    });

    it('link prediction proposes the missing a↔b edge', () => {
        const pred = linkPrediction(shared, { method: 'common-neighbors' });
        expect(pred[0]).toMatchObject({ source: 'a', target: 'b', score: 2 });
    });
});

describe('suspicionScore', () => {
    it('ranks a hub node highest with a 0–100 explainable score', () => {
        const scores = suspicionScore(g);
        expect(['a', 'c']).toContain(scores[0].id); // the two degree-3 hubs
        expect(scores[0].score).toBeGreaterThan(0);
        expect(scores[0].score).toBeLessThanOrEqual(100);
        expect(scores[0].factors).toHaveProperty('betweenness');
    });

    it('honors custom factor weights and the node cap', () => {
        const only = suspicionScore(g, { degree: 1, betweenness: 0, pageRank: 0, core: 0, triangles: 0 });
        expect(only[0].factors.degree).toBeGreaterThan(0);
        const big: G6GraphData = { nodes: Array.from({ length: ANALYSIS_NODE_CAP + 1 }, (_, i) => node(`n${i}`)), edges: [] };
        expect(() => suspicionScore(big)).toThrow(/capped/);
    });
});
