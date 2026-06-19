import { NodeKind } from 'app/inspecto/api';
import { AuthoredFlow, FlowCombined, FlowGraph, FlowNode, FlowNodeType } from 'app/inspecto/api';
import { G6GraphData, nodeColor } from 'app/modules/admin/catalog/catalog-graph';

/**
 * Pure mappers that turn the flow-graph projection (GET /flows/{id}/graph) into AntV G6 data for the
 * shared {@link GraphViewComponent}, plus palette grouping. Kept free of Angular/G6 imports so they
 * unit-test without TestBed.
 *
 * <p>The G6 host keys shape + outline colour off a catalog {@link NodeKind}; a flow node's
 * {@link FlowNode.category} is mapped onto a NodeKind purely for that visual reuse (so colours come
 * from the existing token palette, never a hardcoded value here).
 */

/** Map a flow node category onto a catalog NodeKind for shape/colour reuse (cosmetic only). */
export function categoryVisualKind(category: string): NodeKind {
    switch (category) {
        case 'SOURCE':    return 'SOURCE';
        case 'PARSE':     return 'SCHEMA';
        case 'TRANSFORM': return 'ENRICHMENT';
        case 'SINK':      return 'TABLE';
        case 'CONTROL':   return 'KPI';
        case 'STORE':     return 'TABLE';   // the synthetic shared-store join node (combined view) reads as a table
        default:          return category as NodeKind;   // NodeKind includes string ⇒ falls back gracefully
    }
}

/** The accent colour for a category (via the catalog token palette) — for the legend / palette dot. */
export function categoryColor(category: string): string {
    return nodeColor(categoryVisualKind(category));
}

/** A flow node's display label: the user-given name if set, else the type label. */
export function nodeDisplayLabel(n: FlowNode): string {
    return n.name && n.name.trim() ? n.name : n.label;
}

/** Map the flow-graph projection to G6 data (reusing the catalog G6 host). */
export function toFlowG6Data(g: FlowGraph): G6GraphData {
    return {
        nodes: g.nodes.map((n) => ({
            id: n.id,
            data: { label: nodeDisplayLabel(n), kind: categoryVisualKind(n.category) },
        })),
        // a flow can carry several edges between the same pair (e.g. data + a route branch), so the id
        // folds in the relationship + row index to stay unique.
        edges: g.edges.map((e, i) => ({
            id: `${e.from}->${e.to}:${e.rel}:${i}`,
            source: e.from,
            target: e.to,
            data: { kind: e.kind === 'route' && e.routeKey ? `route:${e.routeKey}` : e.rel },
        })),
    };
}

/**
 * Map the combined pipeline+job topology (GET /flows/combined) to G6 data: flow nodes (namespaced ids)
 * plus the synthetic `STORE` join nodes, with the store-join edges ({@code produces}/{@code consumes})
 * drawn alongside the intra-flow edges. Node ids are already unique (flow nodes `<flow>/<node>`, store
 * nodes `store:<name>`), so they're used verbatim.
 */
export function toCombinedG6Data(c: FlowCombined): G6GraphData {
    return {
        nodes: c.nodes.map((n) => ({
            id: n.id,
            data: {
                label: n.category === 'STORE' ? (n.store ?? n.label) : nodeDisplayLabel(n),
                kind: categoryVisualKind(n.category),
            },
        })),
        edges: c.edges.map((e, i) => ({
            id: `${e.from}->${e.to}:${e.rel}:${i}`,
            source: e.from,
            target: e.to,
            data: { kind: e.kind === 'route' && e.routeKey ? `route:${e.routeKey}` : e.rel },
        })),
    };
}

/** A node-type → category lookup built from the palette catalog (authored nodes carry only their type). */
export function typeCategoryMap(types: FlowNodeType[]): Map<string, string> {
    return new Map(types.map((t) => [t.type, t.category]));
}

/**
 * Map an authored flow (config-bearing, from GET …/raw) to G6 data for the editor host. A node's category —
 * which drives shape + outline colour — is resolved from the palette ({@link typeCategoryMap}); an unknown
 * type falls back to TRANSFORM so a plugin/unknown node still renders.
 */
export function authoredToG6(flow: AuthoredFlow, typeCat: Map<string, string>): G6GraphData {
    return {
        nodes: flow.nodes.map((n) => ({
            id: n.id,
            data: {
                label: n.name && n.name.trim() ? n.name : n.id,
                kind: categoryVisualKind(typeCat.get(n.type) ?? 'TRANSFORM'),
            },
        })),
        edges: flow.edges.map((e, i) => ({
            id: `${e.from}->${e.to}:${e.rel}:${i}`,
            source: e.from,
            target: e.to,
            data: { kind: e.rel },
        })),
    };
}

/** The stable category order for the palette (unknown/plugin categories fall after, in first-seen order). */
export const CATEGORY_ORDER: readonly string[] = ['SOURCE', 'PARSE', 'TRANSFORM', 'SINK', 'CONTROL'];

/** The legend categories for the combined view — the flow categories plus the synthetic shared store. */
export const COMBINED_CATEGORY_ORDER: readonly string[] = [...CATEGORY_ORDER, 'STORE'];

export interface NodeTypeGroup {
    category: string;
    types: FlowNodeType[];
}

/** Group node types by category for the palette, in {@link CATEGORY_ORDER} (unknown categories last). */
export function groupByCategory(types: FlowNodeType[]): NodeTypeGroup[] {
    const byCat = new Map<string, FlowNodeType[]>();
    for (const t of types) {
        const arr = byCat.get(t.category) ?? [];
        arr.push(t);
        byCat.set(t.category, arr);
    }
    const ordered: NodeTypeGroup[] = [];
    for (const c of CATEGORY_ORDER) {
        const ts = byCat.get(c);
        if (ts) {
            ordered.push({ category: c, types: ts });
            byCat.delete(c);
        }
    }
    for (const [category, ts] of byCat) ordered.push({ category, types: ts });
    return ordered;
}
