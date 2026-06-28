import { MetadataEdge, MetadataNode, NodeKind } from 'app/inspecto/api';
import { ICON_COLOR_SWATCHES, NODE_KIND_COLORS, NODE_KIND_FALLBACK } from 'app/inspecto/theme/chart-tokens';

/**
 * Pure mappers that turn a {@link MetadataGraph} into AntV G6 v5 graph data.
 * Kept free of Angular/G6 imports so they unit-test without TestBed.
 */

// The G6 graph-data types now live in the shared `inspecto/graph` lib (so framework-agnostic libraries — the
// component-model reuse-graph — can target the GraphViewComponent host without importing this feature).
// Imported for this file's own mappers and re-exported so the existing catalog / flow importers stay unchanged.
import type { G6Edge, G6GraphData, G6Node } from 'app/inspecto/graph';
export type { G6Edge, G6GraphData, G6Node };

export { ICON_COLOR_SWATCHES };

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
 * Curated library of monochrome line glyphs (24×24 viewBox, path data only) offered in the configurable-icon
 * picker. Keyed by a stable name persisted in the icon map. Extends the original per-kind set with common
 * processor shapes (file/db/stream/filter/route/merge/aggregate/alert/write/…).
 */
export const GLYPH_LIBRARY: Record<string, string> = {
    'arrow-in':   '<path d="M12 3v9m0 0-4-4m4 4 4-4M4 16h16v4H4z"/>',
    'lines':      '<path d="M5 6h14M5 10h14M5 14h10M5 18h8"/>',
    'transform':  '<path d="M4 8h13l-3-3M20 16H7l3 3"/>',
    'cylinder':   '<path d="M12 3c3.9 0 7 1.3 7 3s-3.1 3-7 3-7-1.3-7-3 3.1-3 7-3z"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6"/>',
    'bell':       '<path d="M12 3a5 5 0 0 0-5 5v3l-2 4h14l-2-4V8a5 5 0 0 0-5-5z"/><path d="M10 20a2 2 0 0 0 4 0"/>',
    'report':     '<path d="M6 3h9l4 4v14H6z"/><path d="M9 12h6M9 16h6"/>',
    'columns':    '<path d="M6 4v16M12 4v16M18 4v16"/>',
    'file':       '<path d="M7 3h7l4 4v14H7z"/><path d="M14 3v4h4"/>',
    'database':   '<path d="M12 3c3.9 0 7 1.1 7 2.5S15.9 8 12 8 5 6.9 5 5.5 8.1 3 12 3z"/><path d="M5 5.5v13C5 19.9 8.1 21 12 21s7-1.1 7-2.5v-13"/>',
    'stream':     '<path d="M4 8c4 0 4-3 8-3s4 3 8 3M4 16c4 0 4-3 8-3s4 3 8 3"/>',
    'filter':     '<path d="M4 5h16l-6 8v6l-4-2v-4z"/>',
    'route':      '<path d="M6 4v6a4 4 0 0 0 4 4h8m0 0-3-3m3 3-3 3"/>',
    'merge':      '<path d="M18 4v6a4 4 0 0 1-4 4H6m0 0 3-3m-3 3 3 3"/>',
    'sigma':      '<path d="M17 5H7l5 7-5 7h10"/>',
    'write':      '<path d="M4 4h12l4 4v12H4z"/><path d="M8 14h8M8 18h8M8 4v5h6"/>',
    'cog':        '<path d="M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"/><path d="M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.5-2.3 1a7 7 0 0 0-1.7-1l-.4-2.5h-4l-.4 2.5a7 7 0 0 0-1.7 1l-2.3-1-2 3.5 2 1.5a7 7 0 0 0 0 2l-2 1.5 2 3.5 2.3-1a7 7 0 0 0 1.7 1l.4 2.5h4l.4-2.5a7 7 0 0 0 1.7-1l2.3 1 2-3.5-2-1.5a7 7 0 0 0 .1-1z"/>',
    'clock':      '<path d="M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z"/><path d="M12 7v5l3 2"/>',
    'bolt':       '<path d="M13 3 4 14h7l-1 7 9-11h-7z"/>',
    'code':       '<path d="m8 7-5 5 5 5M16 7l5 5-5 5"/>',
    'globe':      '<path d="M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z"/><path d="M3 12h18M12 3c2.5 2.5 2.5 15 0 18M12 3c-2.5 2.5-2.5 15 0 18"/>',
};

/** Default glyph name per catalog node kind (the fallback when the icon map has no entry). */
const KIND_GLYPH: Record<string, string> = {
    SOURCE: 'arrow-in', SCHEMA: 'lines', ENRICHMENT: 'transform', TABLE: 'cylinder',
    KPI: 'bell', REPORT: 'report', COLUMN: 'columns',
};

/** Build a data-URI SVG icon from a glyph's path data + a stroke colour (used as the G6 node icon shape). */
export function iconDataUri(glyphPath: string, color: string): string {
    const svg =
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" ` +
        `stroke="${color}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${glyphPath}</svg>`;
    return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

/** A data-URI SVG icon for a node kind (stroked in its accent colour) — the built-in fallback. */
export function nodeIcon(kind: NodeKind): string {
    const glyph = GLYPH_LIBRARY[KIND_GLYPH[kind] ?? 'transform'] ?? GLYPH_LIBRARY['transform'];
    return iconDataUri(glyph, nodeColor(kind));
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
