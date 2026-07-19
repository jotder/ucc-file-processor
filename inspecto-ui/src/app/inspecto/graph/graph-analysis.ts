import type { GraphDirection } from 'app/inspecto/api';
import type { G6GraphData } from './graph-types';

/**
 * Pure, source-agnostic graph analysis over the shared {@link G6GraphData} shape — the Link Analysis
 * Studio's algorithm library (plan §3 P1). Framework-free; every function returns new data and never
 * mutates its input. Guarded by {@link ANALYSIS_NODE_CAP} where the algorithm is super-linear.
 */

/** Above this node count the super-linear analyses (betweenness) refuse to run. */
export const ANALYSIS_NODE_CAP = 2000;

/** A highlightable analysis result: the node/edge ids to emphasize on the canvas. */
export interface GraphSelection {
    nodeIds: string[];
    edgeIds: string[];
}

interface Adjacency {
    /** nodeId → outgoing [neighborId, edgeId][] */
    out: Map<string, [string, string][]>;
    /** nodeId → incoming [neighborId, edgeId][] */
    in: Map<string, [string, string][]>;
}

function adjacency(g: G6GraphData): Adjacency {
    const out = new Map<string, [string, string][]>();
    const inn = new Map<string, [string, string][]>();
    for (const n of g.nodes) {
        out.set(n.id, []);
        inn.set(n.id, []);
    }
    for (const e of g.edges) {
        out.get(e.source)?.push([e.target, e.id]);
        inn.get(e.target)?.push([e.source, e.id]);
    }
    return { out, in: inn };
}

function neighborsOf(adj: Adjacency, id: string, direction: GraphDirection): [string, string][] {
    switch (direction) {
        case 'out': return adj.out.get(id) ?? [];
        case 'in':  return adj.in.get(id) ?? [];
        default:    return [...(adj.out.get(id) ?? []), ...(adj.in.get(id) ?? [])];
    }
}

/** BFS shortest path between two nodes, or `null` when disconnected. Includes both endpoints. */
export function shortestPath(g: G6GraphData, fromId: string, toId: string, direction: GraphDirection = 'both'): GraphSelection | null {
    if (fromId === toId) return { nodeIds: [fromId], edgeIds: [] };
    const adj = adjacency(g);
    if (!adj.out.has(fromId) || !adj.out.has(toId)) return null;
    const prev = new Map<string, { node: string; edge: string }>();
    const queue = [fromId];
    const seen = new Set([fromId]);
    while (queue.length) {
        const cur = queue.shift()!;
        for (const [next, edgeId] of neighborsOf(adj, cur, direction)) {
            if (seen.has(next)) continue;
            seen.add(next);
            prev.set(next, { node: cur, edge: edgeId });
            if (next === toId) {
                const nodeIds = [toId];
                const edgeIds: string[] = [];
                let at = toId;
                while (at !== fromId) {
                    const p = prev.get(at)!;
                    edgeIds.unshift(p.edge);
                    nodeIds.unshift(p.node);
                    at = p.node;
                }
                return { nodeIds, edgeIds };
            }
            queue.push(next);
        }
    }
    return null;
}

/**
 * All simple paths between two nodes up to `limit` paths (DFS, depth-capped at `maxHops`).
 * Kept for V1 surfacing but implemented here since the MVP path tab exposes a "more paths" affordance.
 */
export function allPaths(
    g: G6GraphData, fromId: string, toId: string,
    opts: { limit?: number; maxHops?: number; direction?: GraphDirection } = {},
): GraphSelection[] {
    const { limit = 10, maxHops = 8, direction = 'both' } = opts;
    const adj = adjacency(g);
    const results: GraphSelection[] = [];
    const nodeStack = [fromId];
    const edgeStack: string[] = [];
    const onPath = new Set([fromId]);
    const walk = (cur: string): void => {
        if (results.length >= limit) return;
        if (cur === toId) {
            results.push({ nodeIds: [...nodeStack], edgeIds: [...edgeStack] });
            return;
        }
        if (nodeStack.length > maxHops) return;
        for (const [next, edgeId] of neighborsOf(adj, cur, direction)) {
            if (onPath.has(next)) continue;
            onPath.add(next);
            nodeStack.push(next);
            edgeStack.push(edgeId);
            walk(next);
            onPath.delete(next);
            nodeStack.pop();
            edgeStack.pop();
        }
    };
    if (adj.out.has(fromId) && adj.out.has(toId)) walk(fromId);
    return results;
}

/** The N-hop neighborhood subgraph around a node (root included; edges within the kept set). */
export function neighborhood(g: G6GraphData, nodeId: string, hops = 1, direction: GraphDirection = 'both'): G6GraphData {
    const adj = adjacency(g);
    const keep = new Set([nodeId]);
    let frontier = [nodeId];
    for (let h = 0; h < hops && frontier.length; h++) {
        const next: string[] = [];
        for (const id of frontier) {
            for (const [nb] of neighborsOf(adj, id, direction)) {
                if (!keep.has(nb)) {
                    keep.add(nb);
                    next.push(nb);
                }
            }
        }
        frontier = next;
    }
    return {
        nodes: g.nodes.filter((n) => keep.has(n.id)),
        edges: g.edges.filter((e) => keep.has(e.source) && keep.has(e.target)),
    };
}

/** A plain-language summary of a node's links, grouped by edge kind and direction. */
export function explainNode(g: G6GraphData, nodeId: string): string {
    const node = g.nodes.find((n) => n.id === nodeId);
    if (!node) return 'Unknown node.';
    const label = (id: string): string => g.nodes.find((n) => n.id === id)?.data.label ?? id;
    const byKind = new Map<string, string[]>();
    for (const e of g.edges) {
        if (e.source === nodeId) {
            const arr = byKind.get(`→ ${e.data.kind}`) ?? [];
            arr.push(label(e.target));
            byKind.set(`→ ${e.data.kind}`, arr);
        } else if (e.target === nodeId) {
            const arr = byKind.get(`← ${e.data.kind}`) ?? [];
            arr.push(label(e.source));
            byKind.set(`← ${e.data.kind}`, arr);
        }
    }
    if (!byKind.size) return `${node.data.label} (${node.data.kind}) has no links in this graph.`;
    const parts = [...byKind.entries()].map(([k, targets]) => `${k}: ${targets.join(', ')}`);
    return `${node.data.label} (${node.data.kind}) — ${parts.join(' · ')}`;
}

/** Node ranking entry for the centrality tables. */
export interface NodeScore {
    id: string;
    label: string;
    score: number;
}

function scored(g: G6GraphData, score: Map<string, number>): NodeScore[] {
    return g.nodes
        .map((n) => ({ id: n.id, label: n.data.label, score: score.get(n.id) ?? 0 }))
        .sort((a, b) => b.score - a.score || a.label.localeCompare(b.label));
}

/** Degree centrality (in + out), descending. */
export function degreeCentrality(g: G6GraphData): NodeScore[] {
    const deg = new Map<string, number>();
    for (const e of g.edges) {
        deg.set(e.source, (deg.get(e.source) ?? 0) + 1);
        deg.set(e.target, (deg.get(e.target) ?? 0) + 1);
    }
    return scored(g, deg);
}

/**
 * Betweenness centrality (Brandes, unweighted, treating the graph as undirected), descending.
 * Throws above {@link ANALYSIS_NODE_CAP} — callers surface that as a typed message.
 */
export function betweennessCentrality(g: G6GraphData): NodeScore[] {
    if (g.nodes.length > ANALYSIS_NODE_CAP) {
        throw new Error(`Betweenness is capped at ${ANALYSIS_NODE_CAP} nodes (graph has ${g.nodes.length}).`);
    }
    const adj = adjacency(g);
    const bc = new Map<string, number>(g.nodes.map((n) => [n.id, 0]));
    for (const s of g.nodes) {
        const stack: string[] = [];
        const preds = new Map<string, string[]>();
        const sigma = new Map<string, number>([[s.id, 1]]);
        const dist = new Map<string, number>([[s.id, 0]]);
        const queue = [s.id];
        while (queue.length) {
            const v = queue.shift()!;
            stack.push(v);
            for (const [w] of neighborsOf(adj, v, 'both')) {
                if (!dist.has(w)) {
                    dist.set(w, dist.get(v)! + 1);
                    queue.push(w);
                }
                if (dist.get(w) === dist.get(v)! + 1) {
                    sigma.set(w, (sigma.get(w) ?? 0) + sigma.get(v)!);
                    const p = preds.get(w) ?? [];
                    p.push(v);
                    preds.set(w, p);
                }
            }
        }
        const delta = new Map<string, number>();
        while (stack.length) {
            const w = stack.pop()!;
            for (const v of preds.get(w) ?? []) {
                const add = (sigma.get(v)! / sigma.get(w)!) * (1 + (delta.get(w) ?? 0));
                delta.set(v, (delta.get(v) ?? 0) + add);
            }
            if (w !== s.id) bc.set(w, bc.get(w)! + (delta.get(w) ?? 0));
        }
    }
    // undirected: every pair counted twice
    for (const [k, v] of bc) bc.set(k, v / 2);
    return scored(g, bc);
}

/**
 * Community detection by synchronous label propagation (undirected, deterministic: ties resolve to
 * the smallest label, nodes iterate in stable id order). Returns nodeId → communityId where the
 * community id is its smallest member nodeId.
 */
export function detectCommunities(g: G6GraphData, maxIterations = 20): Map<string, string> {
    const adj = adjacency(g);
    const ids = g.nodes.map((n) => n.id).sort();
    const label = new Map<string, string>(ids.map((id) => [id, id]));
    for (let it = 0; it < maxIterations; it++) {
        let changed = false;
        const next = new Map(label);
        for (const id of ids) {
            const counts = new Map<string, number>();
            for (const [nb] of neighborsOf(adj, id, 'both')) {
                const l = label.get(nb)!;
                counts.set(l, (counts.get(l) ?? 0) + 1);
            }
            if (!counts.size) continue;
            // Switch only on a STRICT improvement over the current label's neighbor count —
            // a plain smallest-label tie-break floods across bridge edges and merges clusters.
            const own = label.get(id)!;
            let best = own;
            let bestCount = counts.get(own) ?? 0;
            for (const [l, c] of counts) {
                if (c > bestCount || (c === bestCount && l !== own && best !== own && l < best)) {
                    best = l;
                    bestCount = c;
                }
            }
            if (best !== own) {
                next.set(id, best);
                changed = true;
            }
        }
        for (const [k, v] of next) label.set(k, v);
        if (!changed) break;
    }
    // Absorb noise singletons: sync propagation oscillates on tiny components (an isolated pair
    // swaps labels forever), stranding connected nodes alone — fold each singleton into the
    // community of its smallest-id neighbor (deterministic; chains of singletons collapse).
    const size = new Map<string, number>();
    for (const l of label.values()) size.set(l, (size.get(l) ?? 0) + 1);
    for (const id of ids) {
        const own = label.get(id)!;
        if (size.get(own) !== 1) continue;
        const nbs = neighborsOf(adj, id, 'both').map(([nb]) => nb).sort();
        if (!nbs.length) continue;
        const target = label.get(nbs[0])!;
        label.set(id, target);
        size.set(own, 0);
        size.set(target, (size.get(target) ?? 0) + 1);
    }
    // normalize each community's id to its smallest member for stability
    const members = new Map<string, string[]>();
    for (const [id, l] of label) {
        const arr = members.get(l) ?? [];
        arr.push(id);
        members.set(l, arr);
    }
    const out = new Map<string, string>();
    for (const arr of members.values()) {
        const cid = [...arr].sort()[0];
        for (const id of arr) out.set(id, cid);
    }
    return out;
}

/**
 * Community detection by Louvain modularity optimization (undirected, unit-weight, deterministic:
 * level nodes visited in id order, moves only on a strict modularity gain). Local-moving +
 * community aggregation to convergence. Returns nodeId → communityId (id = the community's smallest
 * member), matching the {@link detectCommunities} contract so the UI shares the list + emphasis code.
 * Throws above {@link ANALYSIS_NODE_CAP} — callers surface that as a typed message.
 */
export function louvainCommunities(g: G6GraphData): Map<string, string> {
    if (g.nodes.length > ANALYSIS_NODE_CAP) {
        throw new Error(`Community detection is capped at ${ANALYSIS_NODE_CAP} nodes (graph has ${g.nodes.length}).`);
    }
    const ids = g.nodes.map((n) => n.id).sort();
    const n = ids.length;
    if (!n) return new Map();
    const index = new Map(ids.map((id, i) => [id, i]));

    // Level-0 weighted undirected graph: adj[i] = nbrIdx → weight (each edge once per endpoint),
    // self[i] = self-loop weight. m2 = Σ degree = 2m, invariant across aggregation levels.
    let adj: Map<number, number>[] = ids.map(() => new Map<number, number>());
    let self = new Array<number>(n).fill(0);
    let m2 = 0;
    for (const e of g.edges) {
        const a = index.get(e.source);
        const b = index.get(e.target);
        if (a == null || b == null) continue;
        if (a === b) { self[a] += 1; m2 += 2; continue; }
        adj[a].set(b, (adj[a].get(b) ?? 0) + 1);
        adj[b].set(a, (adj[b].get(a) ?? 0) + 1);
        m2 += 2;
    }
    if (m2 === 0) return new Map(ids.map((id) => [id, id])); // no edges ⇒ singletons

    let membership = ids.map((_, i) => i); // original node idx → current-level node idx
    for (;;) {
        const size = adj.length;
        const deg = new Array<number>(size).fill(0);
        for (let i = 0; i < size; i++) {
            let d = 2 * self[i];
            for (const w of adj[i].values()) d += w;
            deg[i] = d;
        }
        const comm = adj.map((_, i) => i);
        const commTot = deg.slice(); // Σtot per community
        let improved = true;
        let moved = false;
        while (improved) {
            improved = false;
            for (let i = 0; i < size; i++) {
                const ci = comm[i];
                const kiIn = new Map<number, number>(); // weight from i to each neighbor community
                for (const [j, w] of adj[i]) {
                    const cj = comm[j];
                    kiIn.set(cj, (kiIn.get(cj) ?? 0) + w);
                }
                commTot[ci] -= deg[i]; // detach i
                let bestC = ci;
                let bestGain = (kiIn.get(ci) ?? 0) - (deg[i] * commTot[ci]) / m2;
                for (const c of [...kiIn.keys()].sort((x, y) => x - y)) {
                    if (c === ci) continue;
                    const gain = kiIn.get(c)! - (deg[i] * commTot[c]) / m2;
                    if (gain > bestGain + 1e-12) { bestGain = gain; bestC = c; }
                }
                commTot[bestC] += deg[i];
                if (bestC !== ci) { comm[i] = bestC; improved = true; moved = true; }
            }
        }
        // Relabel this level's communities to contiguous ids and fold into the original membership.
        const remap = new Map<number, number>();
        for (const c of comm) if (!remap.has(c)) remap.set(c, remap.size);
        const newSize = remap.size;
        membership = membership.map((lvl) => remap.get(comm[lvl])!);
        if (!moved || newSize === size) break; // converged

        // Aggregate communities into super-nodes for the next level.
        const nAdj: Map<number, number>[] = Array.from({ length: newSize }, () => new Map<number, number>());
        const nSelf = new Array<number>(newSize).fill(0);
        for (let i = 0; i < size; i++) nSelf[remap.get(comm[i])!] += self[i];
        for (let i = 0; i < size; i++) {
            const ci = remap.get(comm[i])!;
            for (const [j, w] of adj[i]) {
                const cj = remap.get(comm[j])!;
                if (ci === cj) { if (i < j) nSelf[ci] += w; }
                else nAdj[ci].set(cj, (nAdj[ci].get(cj) ?? 0) + w);
            }
        }
        adj = nAdj;
        self = nSelf;
    }

    // Group by final community, label each by its smallest member id.
    const members = new Map<number, string[]>();
    for (let i = 0; i < n; i++) {
        const arr = members.get(membership[i]) ?? [];
        arr.push(ids[i]);
        members.set(membership[i], arr);
    }
    const out = new Map<string, string>();
    for (const arr of members.values()) {
        const rep = [...arr].sort()[0];
        for (const id of arr) out.set(id, rep);
    }
    return out;
}

/** Connected components (undirected), largest first — each as a node-id list. */
export function connectedComponents(g: G6GraphData): string[][] {
    const adj = adjacency(g);
    const seen = new Set<string>();
    const comps: string[][] = [];
    for (const n of g.nodes) {
        if (seen.has(n.id)) continue;
        const comp: string[] = [];
        const queue = [n.id];
        seen.add(n.id);
        while (queue.length) {
            const cur = queue.shift()!;
            comp.push(cur);
            for (const [nb] of neighborsOf(adj, cur, 'both')) {
                if (!seen.has(nb)) {
                    seen.add(nb);
                    queue.push(nb);
                }
            }
        }
        comps.push(comp);
    }
    return comps.sort((a, b) => b.length - a.length);
}

/**
 * Merge several {@link G6GraphData} graphs into one: nodes dedup by id (first graph wins the node's
 * display data), edges dedup by id (a genuine duplicate — same source/target/kind — collapses
 * naturally). The shared merge primitive behind multi-root seeds (lineage/provenance) and
 * multi-entity/multi-dataset mapping (entity-projection's {@code mergeProjectedGraphs}, which wraps
 * this to also OR the server-truncation flag).
 */
export function mergeGraphs(graphs: G6GraphData[]): G6GraphData {
    const nodes = new Map<string, G6GraphData['nodes'][number]>();
    const edges = new Map<string, G6GraphData['edges'][number]>();
    for (const g of graphs) {
        for (const n of g.nodes) if (!nodes.has(n.id)) nodes.set(n.id, n);
        for (const e of g.edges) if (!edges.has(e.id)) edges.set(e.id, e);
    }
    return { nodes: [...nodes.values()], edges: [...edges.values()] };
}

/** Case-insensitive node search over label + id. */
export function searchNodes(g: G6GraphData, text: string): string[] {
    const t = text.trim().toLowerCase();
    if (!t) return [];
    return g.nodes
        .filter((n) => n.data.label.toLowerCase().includes(t) || n.id.toLowerCase().includes(t))
        .map((n) => n.id);
}

/**
 * Filter the graph to the given node/edge kinds (empty list = no filter on that axis).
 * Edges survive only when both endpoints survive.
 */
export function filterByKinds(g: G6GraphData, nodeKinds: string[], edgeKinds: string[]): G6GraphData {
    const nodes = nodeKinds.length ? g.nodes.filter((n) => nodeKinds.includes(n.data.kind)) : g.nodes;
    const keep = new Set(nodes.map((n) => n.id));
    const edges = g.edges.filter(
        (e) => keep.has(e.source) && keep.has(e.target) && (!edgeKinds.length || edgeKinds.includes(e.data.kind)),
    );
    return { nodes, edges };
}

/** One step of a {@link matchPattern} motif. `undefined` kind = wildcard; on step 0 edge fields are ignored. */
export interface PatternStep {
    /** The kind the node at this step must be; wildcard when absent. */
    nodeKind?: string;
    /** The kind the edge into this step must be (base kind, `calls · 2` ⇒ `calls`); wildcard when absent. */
    edgeKind?: string;
    /** The traversal direction from the previous step into this one; defaults to `out`. */
    direction?: GraphDirection;
}

/** Base relationship kind — the folded-count suffix (`calls · 2`) stripped, mirroring the canvas. */
function baseKind(kind: unknown): string {
    return String(kind ?? '').split(' · ')[0];
}

/**
 * Find every simple path matching an ordered node/edge-kind **motif** (a path pattern, not full
 * subgraph isomorphism — the deliberate MVP shape). Step 0 constrains the start node; each later step
 * traverses one edge (matching `edgeKind`/`direction`) to a node matching `nodeKind`. No node repeats
 * within a match. Returns one {@link GraphSelection} per match, capped at `limit` (default 200).
 */
export function matchPattern(g: G6GraphData, steps: PatternStep[], opts: { limit?: number } = {}): GraphSelection[] {
    const { limit = 200 } = opts;
    if (!steps.length) return [];
    const adj = adjacency(g);
    const nodeKind = new Map(g.nodes.map((nd) => [nd.id, nd.data.kind]));
    const edgeKind = new Map(g.edges.map((e) => [e.id, baseKind(e.data.kind)]));
    const nodeOk = (id: string, k?: string): boolean => !k || nodeKind.get(id) === k;

    const results: GraphSelection[] = [];
    const nodePath: string[] = [];
    const edgePath: string[] = [];
    const onPath = new Set<string>();
    const walk = (stepIdx: number): void => {
        if (results.length >= limit) return;
        if (stepIdx >= steps.length) {
            results.push({ nodeIds: [...nodePath], edgeIds: [...edgePath] });
            return;
        }
        const step = steps[stepIdx];
        const cur = nodePath[nodePath.length - 1];
        for (const [next, edgeId] of neighborsOf(adj, cur, step.direction ?? 'out')) {
            if (onPath.has(next)) continue;
            if (step.edgeKind && edgeKind.get(edgeId) !== step.edgeKind) continue;
            if (!nodeOk(next, step.nodeKind)) continue;
            onPath.add(next);
            nodePath.push(next);
            edgePath.push(edgeId);
            walk(stepIdx + 1);
            onPath.delete(next);
            nodePath.pop();
            edgePath.pop();
            if (results.length >= limit) return;
        }
    };
    for (const start of g.nodes) {
        if (results.length >= limit) break;
        if (!nodeOk(start.id, steps[0].nodeKind)) continue;
        nodePath.push(start.id);
        onPath.add(start.id);
        walk(1);
        onPath.delete(start.id);
        nodePath.pop();
    }
    return results;
}

/**
 * True when the graph is a forest — every node has ≤1 parent and there are no cycles (Kahn's peel) —
 * so the tree layouts (mind map / org chart / radial tree) can lay it out. Empty graph ⇒ false.
 */
export function isForest(g: G6GraphData): boolean {
    if (!g.nodes.length) return false;
    const indeg = new Map<string, number>();
    const out = new Map<string, string[]>();
    for (const n of g.nodes) {
        indeg.set(n.id, 0);
        out.set(n.id, []);
    }
    for (const e of g.edges) {
        if (!indeg.has(e.source) || !indeg.has(e.target)) continue;
        indeg.set(e.target, indeg.get(e.target)! + 1);
        out.get(e.source)!.push(e.target);
    }
    if ([...indeg.values()].some((d) => d > 1)) return false; // a 2-parent node isn't a tree
    const queue = [...indeg.entries()].filter(([, d]) => d === 0).map(([id]) => id);
    const seen = new Set<string>();
    while (queue.length) {
        const id = queue.shift()!;
        if (seen.has(id)) continue;
        seen.add(id);
        for (const t of out.get(id) ?? []) {
            indeg.set(t, indeg.get(t)! - 1);
            if (indeg.get(t) === 0) queue.push(t);
        }
    }
    return seen.size === g.nodes.length; // leftover ⇒ a cycle
}

/** All nodes strictly downstream of `rootId` (BFS along outgoing edges; the root excluded). */
export function descendants(g: G6GraphData, rootId: string): Set<string> {
    const out = new Map<string, string[]>();
    for (const e of g.edges) out.set(e.source, [...(out.get(e.source) ?? []), e.target]);
    const seen = new Set<string>();
    const queue = [...(out.get(rootId) ?? [])];
    while (queue.length) {
        const id = queue.shift()!;
        if (id === rootId || seen.has(id)) continue;
        seen.add(id);
        queue.push(...(out.get(id) ?? []));
    }
    return seen;
}

/**
 * Collapse-branches view (Link Analysis): hide everything strictly downstream of each collapsed
 * root (the roots themselves stay visible), dropping edges that lose an endpoint.
 */
export function collapseBranches(g: G6GraphData, collapsedRoots: string[]): G6GraphData {
    if (!collapsedRoots.length) return g;
    const hidden = new Set<string>();
    for (const root of collapsedRoots) for (const id of descendants(g, root)) hidden.add(id);
    for (const root of collapsedRoots) hidden.delete(root); // a collapsed root under another stays visible
    const nodes = g.nodes.filter((n) => !hidden.has(n.id));
    const edges = g.edges.filter((e) => !hidden.has(e.source) && !hidden.has(e.target));
    return { nodes, edges };
}
