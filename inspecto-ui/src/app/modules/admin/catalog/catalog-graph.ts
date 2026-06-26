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

/** Minimal monochrome glyphs (24×24 viewBox) per node kind, drawn in the kind's accent colour. */
const NODE_GLYPH: Record<string, string> = {
    SOURCE:     '<path d="M12 3v9m0 0-4-4m4 4 4-4M4 16h16v4H4z"/>',
    SCHEMA:     '<path d="M5 6h14M5 10h14M5 14h10M5 18h8"/>',
    ENRICHMENT: '<path d="M4 8h13l-3-3M20 16H7l3 3"/>',
    TABLE:      '<path d="M12 3c3.9 0 7 1.3 7 3s-3.1 3-7 3-7-1.3-7-3 3.1-3 7-3z"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6"/>',
    KPI:        '<path d="M12 3a5 5 0 0 0-5 5v3l-2 4h14l-2-4V8a5 5 0 0 0-5-5z"/><path d="M10 20a2 2 0 0 0 4 0"/>',
    REPORT:     '<path d="M6 3h9l4 4v14H6z"/><path d="M9 12h6M9 16h6"/>',
    COLUMN:     '<path d="M6 4v16M12 4v16M18 4v16"/>',
};

/** A data-URI SVG icon for a node kind (stroked in its accent colour) for the G6 node icon shape. */
export function nodeIcon(kind: NodeKind): string {
    const glyph = NODE_GLYPH[kind] ?? NODE_GLYPH['ENRICHMENT'];
    const svg =
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" ` +
        `stroke="${nodeColor(kind)}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${glyph}</svg>`;
    return `data:image/svg+xml,${encodeURIComponent(svg)}`;
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
