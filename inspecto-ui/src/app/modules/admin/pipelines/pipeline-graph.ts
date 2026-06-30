import { NodeKind } from 'app/inspecto/api';
import {
    AuthoredPipeline,
    AuthoredNode,
    ComponentType,
    PipelineCombined,
    PipelineGraph,
    PipelineNode,
    PipelineNodeType,
    IconMap,
    ProvenanceCount,
} from 'app/inspecto/api';
import { GLYPH_LIBRARY, G6GraphData, iconDataUri, nodeColor, nodeIcon } from 'app/modules/admin/catalog/catalog-graph';

/**
 * Pure mappers that turn the flow-graph projection (GET /flows/{id}/graph) into AntV G6 data for the
 * shared {@link GraphViewComponent}, plus palette grouping. Kept free of Angular/G6 imports so they
 * unit-test without TestBed.
 *
 * <p>The G6 host keys shape + outline colour off a catalog {@link NodeKind}; a flow node's
 * {@link PipelineNode.category} is mapped onto a NodeKind purely for that visual reuse (so colours come
 * from the existing token palette, never a hardcoded value here).
 */

/** The registry component a node category binds (parser→grammar, transform, sink); null for sources/control. */
export function bindKindFor(category: string): ComponentType | null {
    switch (category) {
        case 'PARSE':     return 'grammar';
        case 'TRANSFORM': return 'transform';
        case 'SINK':      return 'sink';
        default:          return null;
    }
}

// ── Node status (canvas state) + flow validation (Stages 2 & 4) ──

/** A node's authoring status, shown on the canvas + inspector. */
export type NodeStatus = 'unconfigured' | 'dangling' | 'configured' | 'tested' | 'rejects';

/** A test outcome recorded for a node after a run-to-here (drives `tested`/`rejects`). */
export type TestOutcome = 'tested' | 'rejects';

/** Glyph prefixed to a node's canvas label so status reads as text, not colour alone ('' = none). */
export function statusGlyph(status: NodeStatus): string {
    switch (status) {
        case 'unconfigured': return '⚠ ';
        case 'dangling':     return '⚠ ';
        case 'tested':       return '✓ ';
        case 'rejects':      return '✕ ';
        default:             return '';
    }
}

/** Human label for a node status (the inspector chip). */
export function statusLabel(status: NodeStatus): string {
    switch (status) {
        case 'unconfigured': return 'Needs config';
        case 'dangling':     return 'Missing component';
        case 'configured':   return 'Configured';
        case 'tested':       return 'Tested';
        case 'rejects':      return 'Has rejects';
    }
}

/**
 * Compute a node's status. A node that binds a component (or a source's connection) but has no `use` ref is
 * `unconfigured`; a bound registry ref absent from `validRefs` is `dangling` (only checked when
 * `checkDangling`, so the canvas doesn't false-flag before the registry has loaded); a recorded test outcome
 * wins over the otherwise-`configured` baseline.
 */
export function computeNodeStatus(
    node: AuthoredNode,
    category: string,
    validRefs: ReadonlySet<string>,
    tested: ReadonlyMap<string, TestOutcome>,
    checkDangling = true,
): NodeStatus {
    const bindKind = bindKindFor(category);
    const needsRef = bindKind != null || category === 'SOURCE';
    const ref = node.use?.trim();
    const hasInlineConfig = !!node.config && Object.keys(node.config).length > 0;
    // Unconfigured only when it binds something but is configured neither by a ref nor inline.
    if (needsRef && !ref && !hasInlineConfig) return 'unconfigured';
    if (checkDangling && ref && bindKind && !validRefs.has(ref)) return 'dangling';
    return tested.get(node.id) ?? 'configured';
}

/** One validation finding for the editor's Validate panel; `error` blocks activation. */
export interface PipelineFinding {
    severity: 'error' | 'warning' | 'info';
    nodeId?: string;
    message: string;
}

/**
 * Validate an authored flow for activation: every node configured + its refs resolvable, a source feeding it,
 * a sink draining it, and no orphan (non-source node with no input). `error`-severity findings block Activate.
 */
export function validatePipeline(
    flow: AuthoredPipeline,
    typeCat: ReadonlyMap<string, string>,
    validRefs: ReadonlySet<string>,
    tested: ReadonlyMap<string, TestOutcome>,
): PipelineFinding[] {
    const findings: PipelineFinding[] = [];
    if (!flow.nodes.length) {
        return [{ severity: 'error', message: 'The pipeline has no nodes.' }];
    }
    const incoming = new Set(flow.edges.map((e) => e.to));
    let hasSource = false;
    let hasSink = false;
    for (const n of flow.nodes) {
        const cat = typeCat.get(n.type) ?? 'TRANSFORM';
        if (cat === 'SOURCE') hasSource = true;
        if (cat === 'SINK') hasSink = true;
        const name = n.name || n.id;
        const status = computeNodeStatus(n, cat, validRefs, tested);
        if (status === 'unconfigured') {
            findings.push({ severity: 'error', nodeId: n.id, message: `${name}: needs configuration.` });
        } else if (status === 'dangling') {
            findings.push({ severity: 'error', nodeId: n.id, message: `${name}: references a missing ${bindKindFor(cat)} (${n.use}).` });
        } else if (status === 'configured') {
            findings.push({ severity: 'info', nodeId: n.id, message: `${name}: not yet tested.` });
        } else if (status === 'rejects') {
            findings.push({ severity: 'warning', nodeId: n.id, message: `${name}: last run had unmatched/dropped rows.` });
        }
        if (cat !== 'SOURCE' && !incoming.has(n.id)) {
            findings.push({ severity: 'warning', nodeId: n.id, message: `${name}: has no input connection.` });
        }
    }
    if (!hasSource) findings.push({ severity: 'warning', message: 'No source node — nothing feeds the pipeline.' });
    if (!hasSink) findings.push({ severity: 'warning', message: 'No writer/sink — the pipeline produces no output.' });
    return findings;
}

/**
 * Resolve a node's configurable icon + colour: an exact `type` rule wins over a `category` rule, and
 * anything unmapped falls back to the built-in per-kind glyph. Returns the data-URI icon + the stroke colour
 * embedded into the G6 node data so the host renders it without knowing the map.
 */
export function resolveNodeIcon(
    type: string | undefined,
    category: string,
    map: IconMap | undefined,
): { iconSrc: string; color: string } {
    const rule = (type ? map?.[type] : undefined) ?? map?.[category];
    if (rule && GLYPH_LIBRARY[rule.glyph]) {
        return { iconSrc: iconDataUri(GLYPH_LIBRARY[rule.glyph], rule.color), color: rule.color };
    }
    const kind = categoryVisualKind(category);
    return { iconSrc: nodeIcon(kind), color: nodeColor(kind) };
}

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

/** Friendly palette group name per category — the user-facing processor taxonomy. */
export function categoryLabel(category: string): string {
    switch (category) {
        case 'SOURCE':    return 'Source';
        case 'PARSE':     return 'Parser';
        case 'TRANSFORM': return 'Transformer';
        case 'SINK':      return 'Writer';
        case 'CONTROL':   return 'Control';
        case 'STORE':     return 'Store';
        default:          return category;
    }
}

/** A flow node's display label: the user-given name if set, else the type label. */
export function nodeDisplayLabel(n: PipelineNode): string {
    return n.name && n.name.trim() ? n.name : n.label;
}

/**
 * Map the flow-graph projection to G6 data (reusing the catalog G6 host). When {@code counts} is supplied
 * (the data-plane provenance overlay, T22), each edge's label gains the record count its source emitted on
 * that relationship and a {@code weight} drives the line width — the structure plane painted with quantities
 * (§11). Edges with no recorded count are left at their default style.
 */
export function toPipelineG6Data(g: PipelineGraph, counts?: Map<string, number>, iconMap?: IconMap): G6GraphData {
    return {
        nodes: g.nodes.map((n) => ({
            id: n.id,
            data: {
                label: nodeDisplayLabel(n),
                kind: categoryVisualKind(n.category),
                ...(iconMap ? resolveNodeIcon(n.type, n.category, iconMap) : {}),
            },
        })),
        // a flow can carry several edges between the same pair (e.g. data + a route branch), so the id
        // folds in the relationship + row index to stay unique.
        edges: g.edges.map((e, i) => {
            const rel = e.kind === 'route' && e.routeKey ? `route:${e.routeKey}` : e.rel;
            const count = counts?.get(`${e.from}|${rel}`);
            return {
                id: `${e.from}->${e.to}:${e.rel}:${i}`,
                source: e.from,
                target: e.to,
                data: count == null
                    ? { kind: rel }
                    : { kind: `${rel} · ${count.toLocaleString()}`, weight: count },
            };
        }),
    };
}

/** Build the {@code <nodeId>|<rel>} → rowCount lookup the overlay paints onto edges. */
export function provenanceCounts(rows: ProvenanceCount[]): Map<string, number> {
    return new Map(rows.map((r) => [`${r.nodeId}|${r.rel}`, r.rowCount]));
}

/**
 * Map the combined pipeline+job topology (GET /flows/combined) to G6 data: flow nodes (namespaced ids)
 * plus the synthetic `STORE` join nodes, with the store-join edges ({@code produces}/{@code consumes})
 * drawn alongside the intra-flow edges. Node ids are already unique (flow nodes `<flow>/<node>`, store
 * nodes `store:<name>`), so they're used verbatim.
 */
export function toCombinedG6Data(c: PipelineCombined, iconMap?: IconMap): G6GraphData {
    return {
        nodes: c.nodes.map((n) => ({
            id: n.id,
            data: {
                label: n.category === 'STORE' ? (n.store ?? n.label) : nodeDisplayLabel(n),
                kind: categoryVisualKind(n.category),
                ...(iconMap ? resolveNodeIcon(n.type, n.category, iconMap) : {}),
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
export function typeCategoryMap(types: PipelineNodeType[]): Map<string, string> {
    return new Map(types.map((t) => [t.type, t.category]));
}

/**
 * Map an authored flow (config-bearing, from GET …/raw) to G6 data for the editor host. A node's category —
 * which drives shape + outline colour — is resolved from the palette ({@link typeCategoryMap}); an unknown
 * type falls back to TRANSFORM so a plugin/unknown node still renders.
 */
export function authoredToG6(
    flow: AuthoredPipeline,
    typeCat: Map<string, string>,
    statusOf?: (node: AuthoredNode) => NodeStatus,
    iconMap?: IconMap,
): G6GraphData {
    return {
        nodes: flow.nodes.map((n) => {
            const category = typeCat.get(n.type) ?? 'TRANSFORM';
            return {
                id: n.id,
                data: {
                    label: n.name && n.name.trim() ? n.name : n.id,
                    kind: categoryVisualKind(category),
                    status: statusOf ? statusOf(n) : 'configured',
                    ...(iconMap ? resolveNodeIcon(n.type, category, iconMap) : {}),
                },
            };
        }),
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
    types: PipelineNodeType[];
}

/** Group node types by category for the palette, in {@link CATEGORY_ORDER} (unknown categories last). */
export function groupByCategory(types: PipelineNodeType[]): NodeTypeGroup[] {
    const byCat = new Map<string, PipelineNodeType[]>();
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
