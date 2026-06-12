import { MetadataEdge, MetadataNode, NodeKind } from 'app/inspecto/api';
import { NODE_KIND_COLORS, NODE_KIND_FALLBACK } from 'app/inspecto/theme/chart-tokens';

/**
 * Pure mappers that turn a {@link MetadataGraph} into AntV G6 v5 graph data.
 * Kept free of Angular/G6 imports so they unit-test without TestBed.
 */

export interface G6Node {
    id: string;
    data: { label: string; kind: NodeKind };
}

/** G6 edges key the endpoints as source/target (the API graph uses from/to). */
export interface G6Edge {
    id: string;
    source: string;
    target: string;
    data: { kind: string };
}

export interface G6GraphData {
    nodes: G6Node[];
    edges: G6Edge[];
}

/** Built-in G6 node type per metadata kind. Unknown kinds fall back to a circle. */
export function nodeShape(kind: NodeKind): string {
    switch (kind) {
        case 'SOURCE':     return 'circle';
        case 'SCHEMA':     return 'rect';
        case 'TABLE':      return 'rect';
        case 'COLUMN':     return 'ellipse';
        case 'KPI':        return 'diamond';
        case 'REPORT':     return 'hexagon';
        case 'ENRICHMENT': return 'triangle';
        default:           return 'circle';
    }
}

/** Accent colour per node kind — drives the legend and the node outline. */
export function nodeColor(kind: NodeKind): string {
    return NODE_KIND_COLORS[kind] ?? NODE_KIND_FALLBACK;
}

/**
 * Map the API graph to G6 data. A graph can carry several edges between the same
 * pair (different kinds), so the edge id folds in the kind and row index to stay unique.
 */
export function toG6Data(nodes: MetadataNode[], edges: MetadataEdge[]): G6GraphData {
    return {
        nodes: nodes.map((n) => ({ id: n.id, data: { label: n.label, kind: n.kind } })),
        edges: edges.map((e, i) => ({
            id: `${e.from}->${e.to}:${e.kind}:${i}`,
            source: e.from,
            target: e.to,
            data: { kind: e.kind },
        })),
    };
}

/** Distinct node kinds present in the graph, paired with their legend colour (stable order). */
export function legendFor(nodes: MetadataNode[]): { kind: NodeKind; fill: string }[] {
    const seen = new Set<NodeKind>();
    const out: { kind: NodeKind; fill: string }[] = [];
    for (const n of nodes) {
        if (!seen.has(n.kind)) {
            seen.add(n.kind);
            out.push({ kind: n.kind, fill: nodeColor(n.kind) });
        }
    }
    return out;
}
