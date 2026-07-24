import type { GraphDirection } from 'app/inspecto/api';
import type { G6Edge, G6GraphData } from './graph-types';

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

/**
 * Timeline filter (Link Analysis V2 — BACKLOG §3): keep only edges whose `attrCol` attribute parses
 * as a date on or before `cutoff` (epoch millis); nodes are left untouched (mirrors {@link filterByKinds}'s
 * pure, non-mutating contract, but this axis trims edges only — a node with no surviving edges just
 * goes unconnected, same as a kind-filtered graph). An edge missing the column, or whose value doesn't
 * parse as a date, is dropped (unknown-time edges don't survive a cutoff). `attrCol` empty = no filter.
 */
export function filterByTime(g: G6GraphData, attrCol: string, cutoff: number): G6GraphData {
    if (!attrCol) return g;
    const edges = g.edges.filter((e) => {
        const raw = e.data.attrs?.[attrCol];
        if (raw == null) return false;
        const t = Date.parse(raw);
        return Number.isFinite(t) && t <= cutoff;
    });
    return { nodes: g.nodes, edges };
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

// ══════════════════════════════════════════════════════════════════════════════════════════════
// V2 — Advanced traversal (weighted paths, cycles, structural cut points, ego networks)
// ══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Tie-strength weight of an edge: the folded duplicate count kept by entity-projection
 * (`calls · 3` ⇒ 3, or a runtime `data.count`), else 1 for a single/unfolded link. Weight is the
 * one place the "how strong is this relationship" signal lives, so every weighted algorithm reads it.
 */
export function edgeWeight(e: G6Edge): number {
    const raw = (e.data as { count?: number }).count;
    if (typeof raw === 'number' && raw > 0) return raw;
    const suffix = String(e.data.kind).split(' · ')[1];
    const n = suffix ? Number(suffix) : NaN;
    return Number.isFinite(n) && n > 0 ? n : 1;
}

/**
 * Cheapest path between two nodes by edge weight (Dijkstra), or `null` when disconnected. Cost per
 * edge is `1 / weight` so **stronger** ties (higher folded count) are cheaper to traverse — the
 * result is the path through the strongest relationships, not merely the fewest hops
 * ({@link shortestPath} already answers the fewest-hops question). Includes both endpoints.
 */
export function weightedShortestPath(
    g: G6GraphData, fromId: string, toId: string, direction: GraphDirection = 'both',
): GraphSelection | null {
    if (fromId === toId) return { nodeIds: [fromId], edgeIds: [] };
    const adj = adjacency(g);
    if (!adj.out.has(fromId) || !adj.out.has(toId)) return null;
    const cost = new Map(g.edges.map((e) => [e.id, 1 / edgeWeight(e)]));
    const dist = new Map<string, number>([[fromId, 0]]);
    const prev = new Map<string, { node: string; edge: string }>();
    const visited = new Set<string>();
    for (;;) {
        let cur: string | null = null;
        let best = Infinity;
        for (const [id, d] of dist) {
            if (!visited.has(id) && d < best) { best = d; cur = id; }
        }
        if (cur === null || cur === toId) break;
        visited.add(cur);
        for (const [next, edgeId] of neighborsOf(adj, cur, direction)) {
            if (visited.has(next)) continue;
            const nd = best + (cost.get(edgeId) ?? 1);
            if (nd < (dist.get(next) ?? Infinity)) {
                dist.set(next, nd);
                prev.set(next, { node: cur, edge: edgeId });
            }
        }
    }
    if (!prev.has(toId)) return null;
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

/**
 * Every simple **directed** cycle, up to `limit` (default 50), each capped at `maxLen` hops
 * (default 8). Canonicalized so each cycle is reported once — only cycles whose lexicographically
 * smallest member is the start node are enumerated (a self-loop is a length-1 cycle). Circular flow
 * (money laundering, call-forwarding loops) is the driving use case, hence directed.
 */
export function findCycles(g: G6GraphData, opts: { limit?: number; maxLen?: number } = {}): GraphSelection[] {
    const { limit = 50, maxLen = 8 } = opts;
    const adj = adjacency(g);
    const results: GraphSelection[] = [];
    const nodePath: string[] = [];
    const edgePath: string[] = [];
    const onPath = new Set<string>();
    const walk = (start: string, cur: string): void => {
        for (const [next, edgeId] of adj.out.get(cur) ?? []) {
            if (results.length >= limit) return;
            if (next === start) {
                results.push({ nodeIds: [...nodePath], edgeIds: [...edgePath, edgeId] });
                continue;
            }
            if (next < start || onPath.has(next) || nodePath.length >= maxLen) continue;
            onPath.add(next);
            nodePath.push(next);
            edgePath.push(edgeId);
            walk(start, next);
            onPath.delete(next);
            nodePath.pop();
            edgePath.pop();
        }
    };
    for (const n of g.nodes) {
        if (results.length >= limit) break;
        onPath.add(n.id);
        nodePath.push(n.id);
        walk(n.id, n.id);
        onPath.delete(n.id);
        nodePath.pop();
    }
    return results;
}

/**
 * Biconnectivity (undirected DFS, Tarjan low-link): articulation-point node ids and bridge edge ids
 * — the single cut points whose removal disconnects the graph, i.e. the structural choke points of a
 * ring. Shared by {@link articulationPoints} and {@link bridges}.
 */
function biconnected(g: G6GraphData): { articulation: Set<string>; bridges: Set<string> } {
    const und = new Map<string, [string, string][]>();
    for (const n of g.nodes) und.set(n.id, []);
    for (const e of g.edges) {
        if (e.source === e.target) continue; // self-loops are irrelevant to cut points
        und.get(e.source)?.push([e.target, e.id]);
        und.get(e.target)?.push([e.source, e.id]);
    }
    const disc = new Map<string, number>();
    const low = new Map<string, number>();
    const articulation = new Set<string>();
    const bridges = new Set<string>();
    let timer = 0;
    // Iterative DFS to avoid deep recursion on long chains.
    for (const root of g.nodes) {
        if (disc.has(root.id)) continue;
        const stack: { node: string; parentEdge: string | null; iter: number }[] = [
            { node: root.id, parentEdge: null, iter: 0 },
        ];
        disc.set(root.id, timer);
        low.set(root.id, timer);
        timer++;
        let rootChildren = 0;
        while (stack.length) {
            const frame = stack[stack.length - 1];
            const nbrs = und.get(frame.node) ?? [];
            if (frame.iter < nbrs.length) {
                const [next, edgeId] = nbrs[frame.iter];
                frame.iter++;
                if (edgeId === frame.parentEdge) continue;
                if (disc.has(next)) {
                    low.set(frame.node, Math.min(low.get(frame.node)!, disc.get(next)!));
                } else {
                    disc.set(next, timer);
                    low.set(next, timer);
                    timer++;
                    if (frame.parentEdge === null) rootChildren++;
                    stack.push({ node: next, parentEdge: edgeId, iter: 0 });
                }
            } else {
                stack.pop();
                const parent = stack[stack.length - 1];
                if (parent) {
                    low.set(parent.node, Math.min(low.get(parent.node)!, low.get(frame.node)!));
                    if (low.get(frame.node)! > disc.get(parent.node)!) bridges.add(frame.parentEdge!);
                    if (parent.parentEdge !== null && low.get(frame.node)! >= disc.get(parent.node)!) {
                        articulation.add(parent.node);
                    }
                }
            }
        }
        if (rootChildren > 1) articulation.add(root.id);
    }
    return { articulation, bridges };
}

/** Articulation points (undirected): nodes whose removal increases the component count. */
export function articulationPoints(g: G6GraphData): string[] {
    return [...biconnected(g).articulation].sort();
}

/** Bridges (undirected): edge ids whose removal increases the component count. */
export function bridges(g: G6GraphData): string[] {
    return [...biconnected(g).bridges].sort();
}

/**
 * Ego network of a node: the 1-hop induced subgraph (the node, its direct neighbors, and every edge
 * among that set — including neighbor-to-neighbor edges). A named, intention-revealing wrapper over
 * {@link neighborhood} at `hops = 1`.
 */
export function egoNetwork(g: G6GraphData, nodeId: string, direction: GraphDirection = 'both'): G6GraphData {
    return neighborhood(g, nodeId, 1, direction);
}

// ══════════════════════════════════════════════════════════════════════════════════════════════
// V2 — Algorithm library (centrality family, cohesive subgroups, flow, similarity, link prediction)
// ══════════════════════════════════════════════════════════════════════════════════════════════

function requireUnderCap(g: G6GraphData, what: string): void {
    if (g.nodes.length > ANALYSIS_NODE_CAP) {
        throw new Error(`${what} is capped at ${ANALYSIS_NODE_CAP} nodes (graph has ${g.nodes.length}).`);
    }
}

/** Distinct-neighbor undirected adjacency (a simple graph view — multi-edges and self-loops folded). */
function undirectedNeighbors(g: G6GraphData): Map<string, Set<string>> {
    const nb = new Map<string, Set<string>>(g.nodes.map((n) => [n.id, new Set<string>()]));
    for (const e of g.edges) {
        if (e.source === e.target) continue;
        nb.get(e.source)?.add(e.target);
        nb.get(e.target)?.add(e.source);
    }
    return nb;
}

/**
 * PageRank (damping 0.85, 60 iterations by default), descending. Dangling nodes redistribute their
 * rank uniformly so the vector stays a probability distribution. Directed: rank flows along edges.
 */
export function pageRank(g: G6GraphData, opts: { damping?: number; iterations?: number } = {}): NodeScore[] {
    const { damping = 0.85, iterations = 60 } = opts;
    const ids = g.nodes.map((n) => n.id);
    const N = ids.length;
    if (!N) return [];
    const outLinks = new Map<string, string[]>(ids.map((id) => [id, []]));
    for (const e of g.edges) outLinks.get(e.source)?.push(e.target);
    const outDeg = new Map(ids.map((id) => [id, outLinks.get(id)!.length]));
    let pr = new Map(ids.map((id) => [id, 1 / N]));
    for (let it = 0; it < iterations; it++) {
        const next = new Map(ids.map((id) => [id, (1 - damping) / N]));
        let dangling = 0;
        for (const id of ids) if (outDeg.get(id) === 0) dangling += pr.get(id)!;
        const danglingShare = (damping * dangling) / N;
        for (const id of ids) {
            const deg = outDeg.get(id)!;
            if (!deg) continue;
            const share = (damping * pr.get(id)!) / deg;
            for (const t of outLinks.get(id)!) next.set(t, next.get(t)! + share);
        }
        for (const id of ids) next.set(id, next.get(id)! + danglingShare);
        pr = next;
    }
    return scored(g, pr);
}

/**
 * Closeness centrality (undirected, Wasserman–Faust normalization for disconnected graphs),
 * descending — high for nodes with short paths to many others. Throws above {@link ANALYSIS_NODE_CAP}.
 */
export function closenessCentrality(g: G6GraphData): NodeScore[] {
    requireUnderCap(g, 'Closeness');
    const adj = adjacency(g);
    const ids = g.nodes.map((n) => n.id);
    const N = ids.length;
    const close = new Map<string, number>();
    for (const s of ids) {
        const dist = new Map<string, number>([[s, 0]]);
        const queue = [s];
        let sum = 0;
        let reach = 0;
        while (queue.length) {
            const v = queue.shift()!;
            for (const [w] of neighborsOf(adj, v, 'both')) {
                if (dist.has(w)) continue;
                dist.set(w, dist.get(v)! + 1);
                sum += dist.get(w)!;
                reach++;
                queue.push(w);
            }
        }
        close.set(s, reach > 0 && N > 1 ? (reach / (N - 1)) * (reach / sum) : 0);
    }
    return scored(g, close);
}

function powerIterate(
    g: G6GraphData, step: (x: Map<string, number>, next: Map<string, number>) => void, iterations: number,
): Map<string, number> {
    const ids = g.nodes.map((n) => n.id);
    let x = new Map(ids.map((id) => [id, 1]));
    for (let it = 0; it < iterations; it++) {
        const next = new Map(ids.map((id) => [id, 0]));
        step(x, next);
        let norm = 0;
        for (const v of next.values()) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm === 0) break;
        for (const [k, v] of next) next.set(k, v / norm);
        x = next;
    }
    return x;
}

/** Eigenvector centrality (undirected, power iteration), descending. Throws above {@link ANALYSIS_NODE_CAP}. */
export function eigenvectorCentrality(g: G6GraphData, iterations = 100): NodeScore[] {
    requireUnderCap(g, 'Eigenvector centrality');
    const adj = adjacency(g);
    const x = powerIterate(g, (cur, next) => {
        for (const id of next.keys()) {
            let s = 0;
            for (const [nb] of neighborsOf(adj, id, 'both')) s += cur.get(nb) ?? 0;
            next.set(id, s);
        }
    }, iterations);
    return scored(g, x);
}

/**
 * Katz centrality (undirected): `x = β + α·Aᵀ·x` iterated to convergence, descending. `alpha` must be
 * below 1/λmax to converge (0.1 is safe for sparse investigation graphs). Throws above the cap.
 */
export function katzCentrality(
    g: G6GraphData, opts: { alpha?: number; beta?: number; iterations?: number } = {},
): NodeScore[] {
    requireUnderCap(g, 'Katz centrality');
    const { alpha = 0.1, beta = 1, iterations = 100 } = opts;
    const adj = adjacency(g);
    const ids = g.nodes.map((n) => n.id);
    let x = new Map(ids.map((id) => [id, 0]));
    for (let it = 0; it < iterations; it++) {
        const next = new Map(ids.map((id) => [id, beta]));
        for (const id of ids) {
            let s = 0;
            for (const [nb] of neighborsOf(adj, id, 'both')) s += x.get(nb) ?? 0;
            next.set(id, beta + alpha * s);
        }
        x = next;
    }
    return scored(g, x);
}

/** Hub and authority scores (HITS, directed, power iteration). Throws above {@link ANALYSIS_NODE_CAP}. */
export interface HitsResult {
    hubs: NodeScore[];
    authorities: NodeScore[];
}

export function hits(g: G6GraphData, iterations = 100): HitsResult {
    requireUnderCap(g, 'HITS');
    const adj = adjacency(g);
    const ids = g.nodes.map((n) => n.id);
    let hub = new Map(ids.map((id) => [id, 1]));
    let auth = new Map(ids.map((id) => [id, 1]));
    const l2 = (m: Map<string, number>): void => {
        let norm = 0;
        for (const v of m.values()) norm += v * v;
        norm = Math.sqrt(norm) || 1;
        for (const [k, v] of m) m.set(k, v / norm);
    };
    for (let it = 0; it < iterations; it++) {
        const nextAuth = new Map(ids.map((id) => [id, 0]));
        for (const id of ids) {
            let s = 0;
            for (const [src] of adj.in.get(id) ?? []) s += hub.get(src) ?? 0;
            nextAuth.set(id, s);
        }
        const nextHub = new Map(ids.map((id) => [id, 0]));
        for (const id of ids) {
            let s = 0;
            for (const [tgt] of adj.out.get(id) ?? []) s += nextAuth.get(tgt) ?? 0;
            nextHub.set(id, s);
        }
        l2(nextAuth);
        l2(nextHub);
        auth = nextAuth;
        hub = nextHub;
    }
    return { hubs: scored(g, hub), authorities: scored(g, auth) };
}

/**
 * k-core decomposition (undirected simple graph): each node's core number — the largest k such that
 * it belongs to a subgraph where every node has degree ≥ k. Returned as {@link NodeScore} (score =
 * core number), descending; high core numbers mark densely interconnected cores (fraud rings).
 */
export function kCore(g: G6GraphData): NodeScore[] {
    const nb = undirectedNeighbors(g);
    const deg = new Map<string, number>([...nb].map(([id, set]) => [id, set.size]));
    const core = new Map<string, number>();
    const remaining = new Set(deg.keys());
    let k = 0;
    while (remaining.size) {
        let min: string | null = null;
        let minDeg = Infinity;
        for (const id of remaining) {
            if (deg.get(id)! < minDeg) { minDeg = deg.get(id)!; min = id; }
        }
        k = Math.max(k, minDeg);
        core.set(min!, k);
        remaining.delete(min!);
        for (const other of nb.get(min!) ?? []) {
            if (remaining.has(other)) deg.set(other, deg.get(other)! - 1);
        }
    }
    return scored(g, core);
}

/** Triangle count per node (undirected): the number of triangles each node participates in, descending. */
export function triangleCount(g: G6GraphData): NodeScore[] {
    const nb = undirectedNeighbors(g);
    const tri = new Map<string, number>(g.nodes.map((n) => [n.id, 0]));
    for (const [id, neighbors] of nb) {
        const arr = [...neighbors];
        let count = 0;
        for (let i = 0; i < arr.length; i++) {
            for (let j = i + 1; j < arr.length; j++) {
                if (nb.get(arr[i])?.has(arr[j])) count++;
            }
        }
        tri.set(id, count);
    }
    return scored(g, tri);
}

/**
 * Maximal cliques (undirected, Bron–Kerbosch with pivoting) of size ≥ `minSize` (default 3), largest
 * first — fully-interconnected groups, the tightest form of a ring. Throws above {@link ANALYSIS_NODE_CAP}.
 */
export function cliques(g: G6GraphData, opts: { minSize?: number } = {}): string[][] {
    requireUnderCap(g, 'Clique detection');
    const { minSize = 3 } = opts;
    const nb = undirectedNeighbors(g);
    const found: string[][] = [];
    const bk = (r: Set<string>, p: Set<string>, x: Set<string>): void => {
        if (!p.size && !x.size) {
            if (r.size >= minSize) found.push([...r].sort());
            return;
        }
        // Pivot = the vertex in P∪X with the most neighbors in P.
        let pivot: string | null = null;
        let bestDeg = -1;
        for (const u of [...p, ...x]) {
            let d = 0;
            for (const w of nb.get(u) ?? []) if (p.has(w)) d++;
            if (d > bestDeg) { bestDeg = d; pivot = u; }
        }
        const candidates = [...p].filter((v) => !(pivot && nb.get(pivot)?.has(v)));
        for (const v of candidates) {
            const vn = nb.get(v) ?? new Set<string>();
            bk(
                new Set([...r, v]),
                new Set([...p].filter((w) => vn.has(w))),
                new Set([...x].filter((w) => vn.has(w))),
            );
            p.delete(v);
            x.add(v);
        }
    };
    bk(new Set(), new Set(nb.keys()), new Set());
    return found.sort((a, b) => b.length - a.length || a[0].localeCompare(b[0]));
}

/** Max-flow value plus the min-cut edges between a source and sink. */
export interface MaxFlowResult {
    value: number;
    /** The saturated min-cut edges (and their endpoints) — the bottleneck between source and sink. */
    minCut: GraphSelection;
}

/**
 * Maximum flow from `sourceId` to `sinkId` (Edmonds–Karp) with edge capacity = {@link edgeWeight},
 * and the resulting min-cut. Directed. Returns `{ value: 0, minCut: empty }` when either endpoint is
 * absent. Throws above {@link ANALYSIS_NODE_CAP}.
 */
export function maxFlow(g: G6GraphData, sourceId: string, sinkId: string): MaxFlowResult {
    requireUnderCap(g, 'Max-flow');
    const empty: MaxFlowResult = { value: 0, minCut: { nodeIds: [], edgeIds: [] } };
    if (sourceId === sinkId) return empty;
    const nodeIds = new Set(g.nodes.map((n) => n.id));
    if (!nodeIds.has(sourceId) || !nodeIds.has(sinkId)) return empty;
    // Residual capacities keyed "a b"; forward edges seed capacity, back edges start at 0.
    const cap = new Map<string, number>();
    const adj = new Map<string, Set<string>>(g.nodes.map((n) => [n.id, new Set<string>()]));
    const key = (a: string, b: string): string => `${a} ${b}`;
    for (const e of g.edges) {
        if (e.source === e.target) continue;
        cap.set(key(e.source, e.target), (cap.get(key(e.source, e.target)) ?? 0) + edgeWeight(e));
        if (!cap.has(key(e.target, e.source))) cap.set(key(e.target, e.source), 0);
        adj.get(e.source)!.add(e.target);
        adj.get(e.target)!.add(e.source);
    }
    let value = 0;
    for (;;) {
        const prev = new Map<string, string>();
        const queue = [sourceId];
        prev.set(sourceId, sourceId);
        while (queue.length) {
            const u = queue.shift()!;
            if (u === sinkId) break;
            for (const v of adj.get(u) ?? []) {
                if (!prev.has(v) && (cap.get(key(u, v)) ?? 0) > 1e-9) {
                    prev.set(v, u);
                    queue.push(v);
                }
            }
        }
        if (!prev.has(sinkId)) break;
        let bottleneck = Infinity;
        for (let v = sinkId; v !== sourceId; v = prev.get(v)!) {
            bottleneck = Math.min(bottleneck, cap.get(key(prev.get(v)!, v))!);
        }
        for (let v = sinkId; v !== sourceId; v = prev.get(v)!) {
            const u = prev.get(v)!;
            cap.set(key(u, v), cap.get(key(u, v))! - bottleneck);
            cap.set(key(v, u), (cap.get(key(v, u)) ?? 0) + bottleneck);
        }
        value += bottleneck;
    }
    // Min-cut: nodes reachable from source in the residual graph; cut edges cross that frontier.
    const reachable = new Set([sourceId]);
    const stack = [sourceId];
    while (stack.length) {
        const u = stack.pop()!;
        for (const v of adj.get(u) ?? []) {
            if (!reachable.has(v) && (cap.get(key(u, v)) ?? 0) > 1e-9) {
                reachable.add(v);
                stack.push(v);
            }
        }
    }
    const cutEdges = g.edges.filter((e) => reachable.has(e.source) && !reachable.has(e.target));
    return {
        value,
        minCut: {
            nodeIds: [...new Set(cutEdges.flatMap((e) => [e.source, e.target]))],
            edgeIds: cutEdges.map((e) => e.id),
        },
    };
}

/**
 * Maximum-weight spanning forest (Kruskal + union-find over the undirected graph, {@link edgeWeight}
 * as weight): the backbone of strongest ties that still touches every reachable node without a cycle.
 * Returns the kept edge ids plus every node they span.
 */
export function maximumSpanningForest(g: G6GraphData): GraphSelection {
    const parent = new Map<string, string>(g.nodes.map((n) => [n.id, n.id]));
    const find = (x: string): string => {
        let r = x;
        while (parent.get(r) !== r) r = parent.get(r)!;
        while (parent.get(x) !== r) { const nx = parent.get(x)!; parent.set(x, r); x = nx; }
        return r;
    };
    const sorted = g.edges
        .filter((e) => e.source !== e.target)
        .map((e) => ({ e, w: edgeWeight(e) }))
        .sort((a, b) => b.w - a.w || a.e.id.localeCompare(b.e.id));
    const edgeIds: string[] = [];
    const nodeIds = new Set<string>();
    for (const { e } of sorted) {
        const rs = find(e.source);
        const rt = find(e.target);
        if (rs === rt) continue;
        parent.set(rs, rt);
        edgeIds.push(e.id);
        nodeIds.add(e.source);
        nodeIds.add(e.target);
    }
    return { nodeIds: [...nodeIds], edgeIds };
}

/**
 * Jaccard neighbor-similarity of every other node to `nodeId`: `|N(a)∩N(b)| / |N(a)∪N(b)|`
 * (undirected, descending). High similarity flags nodes that share the same associates — the
 * device/identity-sharing signal.
 */
export function jaccardSimilarity(g: G6GraphData, nodeId: string): NodeScore[] {
    const nb = undirectedNeighbors(g);
    const mine = nb.get(nodeId);
    if (!mine) return [];
    const sim = new Map<string, number>();
    for (const other of nb.keys()) {
        if (other === nodeId) continue;
        const theirs = nb.get(other)!;
        let inter = 0;
        for (const x of mine) if (theirs.has(x)) inter++;
        const union = mine.size + theirs.size - inter;
        sim.set(other, union ? inter / union : 0);
    }
    return g.nodes
        .filter((n) => n.id !== nodeId)
        .map((n) => ({ id: n.id, label: n.data.label, score: sim.get(n.id) ?? 0 }))
        .sort((a, b) => b.score - a.score || a.label.localeCompare(b.label));
}

/** A predicted (not-yet-present) link between two nodes, with a likelihood score. */
export interface PredictedLink {
    source: string;
    target: string;
    sourceLabel: string;
    targetLabel: string;
    score: number;
}

/**
 * Link prediction over non-adjacent node pairs (undirected), top `limit` (default 20) by score.
 * `method`: `common-neighbors` (raw shared-neighbor count) or `adamic-adar` (shared neighbors
 * weighted by `1/log(degree)`, the default — rarer common associates count for more). Throws above
 * {@link ANALYSIS_NODE_CAP}.
 */
export function linkPrediction(
    g: G6GraphData, opts: { method?: 'common-neighbors' | 'adamic-adar'; limit?: number } = {},
): PredictedLink[] {
    requireUnderCap(g, 'Link prediction');
    const { method = 'adamic-adar', limit = 20 } = opts;
    const nb = undirectedNeighbors(g);
    const label = new Map(g.nodes.map((n) => [n.id, n.data.label]));
    const ids = [...nb.keys()].sort();
    const out: PredictedLink[] = [];
    for (let i = 0; i < ids.length; i++) {
        const a = ids[i];
        const an = nb.get(a)!;
        for (let j = i + 1; j < ids.length; j++) {
            const b = ids[j];
            if (an.has(b)) continue; // already linked
            const bn = nb.get(b)!;
            let score = 0;
            for (const c of an) {
                if (!bn.has(c)) continue;
                const deg = nb.get(c)!.size;
                score += method === 'adamic-adar' ? (deg > 1 ? 1 / Math.log(deg) : 0) : 1;
            }
            if (score > 0) {
                out.push({ source: a, target: b, sourceLabel: label.get(a) ?? a, targetLabel: label.get(b) ?? b, score });
            }
        }
    }
    return out.sort((x, y) => y.score - x.score || x.source.localeCompare(y.source)).slice(0, limit);
}

// ══════════════════════════════════════════════════════════════════════════════════════════════
// V2 — Suspicious-node scoring (a composite risk signal over the centrality family)
// ══════════════════════════════════════════════════════════════════════════════════════════════

/** Relative weight of each factor in the composite suspicion score (each defaults to 1). */
export interface SuspicionWeights {
    degree?: number;
    betweenness?: number;
    pageRank?: number;
    core?: number;
    triangles?: number;
}

/** A suspicion score with its normalized (0–1) factor breakdown, so the UI can explain *why*. */
export interface SuspicionScore extends NodeScore {
    factors: { degree: number; betweenness: number; pageRank: number; core: number; triangles: number };
}

function normalize(scores: NodeScore[]): Map<string, number> {
    const max = scores.reduce((m, s) => Math.max(m, s.score), 0);
    const out = new Map<string, number>();
    for (const s of scores) out.set(s.id, max > 0 ? s.score / max : 0);
    return out;
}

/**
 * Composite **suspicious-node score** (0–100, descending): a weighted blend of five normalized
 * signals — degree, betweenness, PageRank, k-core, and triangle participation — that surface the
 * over-connected brokers and dense-cluster members an investigator should look at first. Weights are
 * tunable (default 1 each); the per-node `factors` breakdown drives risk sizing/colouring on the
 * canvas and an explainable ranking. Throws above {@link ANALYSIS_NODE_CAP} (betweenness dominates).
 */
export function suspicionScore(g: G6GraphData, weights: SuspicionWeights = {}): SuspicionScore[] {
    requireUnderCap(g, 'Suspicion scoring');
    const w = { degree: 1, betweenness: 1, pageRank: 1, core: 1, triangles: 1, ...weights };
    const deg = normalize(degreeCentrality(g));
    const btw = normalize(betweennessCentrality(g));
    const pr = normalize(pageRank(g));
    const core = normalize(kCore(g));
    const tri = normalize(triangleCount(g));
    const wSum = w.degree + w.betweenness + w.pageRank + w.core + w.triangles || 1;
    return g.nodes
        .map((n) => {
            const factors = {
                degree: deg.get(n.id) ?? 0,
                betweenness: btw.get(n.id) ?? 0,
                pageRank: pr.get(n.id) ?? 0,
                core: core.get(n.id) ?? 0,
                triangles: tri.get(n.id) ?? 0,
            };
            const blend =
                w.degree * factors.degree +
                w.betweenness * factors.betweenness +
                w.pageRank * factors.pageRank +
                w.core * factors.core +
                w.triangles * factors.triangles;
            return { id: n.id, label: n.data.label, score: (blend / wSum) * 100, factors };
        })
        .sort((a, b) => b.score - a.score || a.label.localeCompare(b.label));
}
