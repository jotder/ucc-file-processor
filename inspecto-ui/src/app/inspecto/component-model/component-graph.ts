import { Component } from './component-types';

/**
 * Generic graph-data shape — a superset of the catalog `G6GraphData`, with `kind` widened to `string` so any
 * ComponentKind id is valid. The registry / reuse-graph view (adoption-plan P3) renders this through the
 * existing G6 host (`GraphViewComponent`). Colors come from tokens via `resolveKind`, never hardcoded here.
 */
export interface GraphNode {
    id: string;
    data: { label: string; kind: string; color?: string; missing?: boolean };
}

export interface GraphEdge {
    id: string;
    source: string;
    target: string;
    data: { kind: string };
}

export interface GraphData {
    nodes: GraphNode[];
    edges: GraphEdge[];
}

export interface ComponentGraphInput {
    /** The universe to graph (or a focused subset). */
    components: Component[];
    /** Optional per-kind presentation (token color). */
    resolveKind?: (kind: string) => { color?: string } | undefined;
}

/**
 * Derive the relationship graph: one node per component plus a `uses` edge from each composite to every
 * part's referent. A reference to a component absent from `components` still renders, as a ghost node
 * (`data.missing`). Pure; emits {@link GraphData}.
 */
export function deriveComponentGraph(input: ComponentGraphInput): GraphData {
    const { components, resolveKind } = input;
    const nodeId = (kind: string, id: string): string => `${kind}/${id}`;
    const known = new Set(components.map((c) => nodeId(c.kind, c.id)));

    const nodes = new Map<string, GraphNode>();
    const edges: GraphEdge[] = [];

    const ensureNode = (kind: string, id: string, label: string, missing: boolean): string => {
        const nid = nodeId(kind, id);
        if (!nodes.has(nid)) {
            const node: GraphNode = { id: nid, data: { label, kind } };
            const color = resolveKind?.(kind)?.color;
            if (color) node.data.color = color;
            if (missing) node.data.missing = true;
            nodes.set(nid, node);
        }
        return nid;
    };

    for (const c of components) ensureNode(c.kind, c.id, c.name, false);

    let seq = 0;
    for (const c of components) {
        const parent = nodeId(c.kind, c.id);
        for (const part of c.parts ?? []) {
            const ref = part.ref;
            let target: string;
            if (ref.inline) {
                target = ensureNode(ref.inline.kind, `${c.id}::${part.partId}`, ref.inline.name, false);
            } else if (ref.id) {
                const present = known.has(nodeId(ref.kind, ref.id));
                target = ensureNode(ref.kind, ref.id, ref.id, !present);
            } else {
                continue; // a ref with neither id nor inline — nothing to point at
            }
            edges.push({ id: `${parent}->${target}:${seq++}`, source: parent, target, data: { kind: 'uses' } });
        }
    }

    return { nodes: [...nodes.values()], edges };
}
