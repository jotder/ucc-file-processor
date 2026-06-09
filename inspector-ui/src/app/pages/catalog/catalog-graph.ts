import { MetadataEdge, MetadataNode, NodeKind } from '../../shared/api';

/**
 * Pure mappers that turn a {@link MetadataGraph} into the shape DevExtreme's `dxDiagram`
 * data-binding expects. Kept free of Angular/DevExtreme imports so they unit-test without TestBed.
 */

/**
 * A node as bound to `dxo-nodes` (keyExpr=id, textExpr=label, typeExpr=shape, styleExpr=style).
 * Only `stroke`/`stroke-width` are carried here — DevExtreme's `styleExpr` ignores `fill`, so the
 * (uniform) node fill is set in CSS (see `dx-styles.scss`) and only the per-kind outline varies.
 */
export interface DiagramNode {
  id: string;
  label: string;
  kind: NodeKind;
  shape: string;
  style: { stroke: string; 'stroke-width': number };
}

/** An edge as bound to `dxo-edges` (keyExpr=id, fromExpr=from, toExpr=to, textExpr=kind). */
export interface DiagramEdge {
  id: string;
  from: string;
  to: string;
  kind: string;
}

/** Built-in DevExtreme diagram shape per node kind. Unknown kinds fall back to a terminator. */
export function nodeShape(kind: NodeKind): string {
  switch (kind) {
    case 'SOURCE':     return 'database';
    case 'SCHEMA':     return 'document';
    case 'TABLE':      return 'rectangle';
    case 'COLUMN':     return 'ellipse';
    case 'KPI':        return 'process';
    case 'REPORT':     return 'hexagon';
    case 'ENRICHMENT': return 'diamond';
    default:           return 'terminator';
  }
}

/** Stroke colour per node kind — drives the legend and the node outline (fill stays themed). */
export function nodeColor(kind: NodeKind): string {
  switch (kind) {
    case 'SOURCE':     return '#5B8FF9';
    case 'SCHEMA':     return '#61DDAA';
    case 'TABLE':      return '#65789B';
    case 'COLUMN':     return '#F6BD16';
    case 'KPI':        return '#7262FD';
    case 'REPORT':     return '#78D3F8';
    case 'ENRICHMENT': return '#F6903D';
    default:           return '#9AA0A6';
  }
}

export function toDiagramNodes(nodes: MetadataNode[]): DiagramNode[] {
  return nodes.map((n) => ({
    id: n.id,
    label: n.label,
    kind: n.kind,
    shape: nodeShape(n.kind),
    style: { stroke: nodeColor(n.kind), 'stroke-width': 2 },
  }));
}

/**
 * Map edges to diagram connectors. A graph can carry several edges between the same pair
 * (different kinds), so the connector key folds in the kind and the row index to stay unique.
 */
export function toDiagramEdges(edges: MetadataEdge[]): DiagramEdge[] {
  return edges.map((e, i) => ({
    id: `${e.from}->${e.to}:${e.kind}:${i}`,
    from: e.from,
    to: e.to,
    kind: e.kind,
  }));
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
