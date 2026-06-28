import { NodeKind } from 'app/inspecto/api';

/**
 * Shared AntV G6 graph-data shape, lifted out of `catalog/catalog-graph.ts` so framework-agnostic libraries
 * (the component-model reuse-graph) can target the existing `GraphViewComponent` host **without importing a
 * feature**. `kind` is a {@link NodeKind} — a known metadata kind or any free string (a `ComponentKind` id) —
 * which the host keys node shape / outline colour off. `iconSrc`/`color` are set when a configurable icon is
 * resolved (flow / pipeline views); `missing` marks a dangling-reference ghost node (the reuse-graph). Colours
 * come from tokens, never hardcoded here.
 */
export interface G6Node {
    id: string;
    data: { label: string; kind: NodeKind; iconSrc?: string; color?: string; missing?: boolean };
}

/** A G6 edge — endpoints as source/target (the API graph uses from/to); `kind` labels the edge. */
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
