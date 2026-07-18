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
 * Pure mappers that turn the flow-graph projection (GET /pipelines/{id}/graph) into AntV G6 data for the
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
        case 'SOURCE':    return 'STREAM';
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
 * Map the combined pipeline+job topology (GET /pipelines/combined) to G6 data: flow nodes (namespaced ids)
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
 * type falls back to TRANSFORM so a plugin/unknown node still renders. When {@code lastRunCounts} is supplied
 * (T17's live last-run overlay — the flow's most recent {@code /provenance} read), each edge's label gains the
 * record count its source emitted on that relationship during the real last run, same painting rule as
 * {@link toPipelineG6Data}'s {@code counts} (edges with no recorded count are left at their default style).
 */
export function authoredToG6(
    flow: AuthoredPipeline,
    typeCat: Map<string, string>,
    statusOf?: (node: AuthoredNode) => NodeStatus,
    iconMap?: IconMap,
    lastRunCounts?: Map<string, number>,
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
        edges: flow.edges.map((e, i) => {
            const count = lastRunCounts?.get(`${e.from}|${e.rel}`);
            return {
                id: `${e.from}->${e.to}:${e.rel}:${i}`,
                source: e.from,
                target: e.to,
                data: count == null
                    ? { kind: e.rel }
                    : { kind: `${e.rel} · ${count.toLocaleString()}`, weight: count },
            };
        }),
    };
}

/**
 * A node's total last-run output (the sum of every relationship it emitted, e.g. {@code data}+{@code dropped})
 * from the {@code nodeId|rel} → rowCount lookup built by {@link provenanceCounts}. {@code null} when the node
 * recorded nothing in that run (not the same as a real {@code 0} — the inspector should read that as "no data").
 */
export function nodeLastRunTotal(nodeId: string, counts: ReadonlyMap<string, number>): number | null {
    let total: number | null = null;
    for (const [key, count] of counts) {
        if (key.startsWith(`${nodeId}|`)) total = (total ?? 0) + count;
    }
    return total;
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

/** Heroicon per palette category for the compact toolbar chips / palette buttons. */
export function paletteHeroIcon(category: string): string {
    switch (category) {
        case 'SOURCE':    return 'heroicons_outline:arrow-down-on-square';
        case 'PARSE':     return 'heroicons_outline:document-text';
        case 'TRANSFORM': return 'heroicons_outline:arrows-right-left';
        case 'SINK':      return 'heroicons_outline:circle-stack';
        case 'CONTROL':   return 'heroicons_outline:bell-alert';
        default:          return 'heroicons_outline:cube';
    }
}

/** Heroicon for a node status (text + icon + colour → never colour alone). */
export function statusIcon(s: NodeStatus): string {
    switch (s) {
        case 'unconfigured': return 'heroicons_outline:exclamation-triangle';
        case 'dangling':     return 'heroicons_outline:x-circle';
        case 'tested':       return 'heroicons_outline:check-circle';
        case 'rejects':      return 'heroicons_outline:exclamation-triangle';
        default:             return 'heroicons_outline:check';
    }
}

/** Token colour for a node status ('' = inherit, for the neutral `configured` state). */
export function statusTint(s: NodeStatus): string {
    switch (s) {
        case 'tested':     return 'var(--gamma-primary)';
        case 'configured': return '';
        default:           return 'var(--gamma-warn)';
    }
}

/** Icon for a validation finding's severity. */
export function findingIcon(sev: PipelineFinding['severity']): string {
    switch (sev) {
        case 'error':   return 'heroicons_outline:x-circle';
        case 'warning': return 'heroicons_outline:exclamation-triangle';
        default:        return 'heroicons_outline:information-circle';
    }
}

/** Token colour for a validation finding's severity ('' = inherit, for `info`). */
export function findingTint(sev: PipelineFinding['severity']): string {
    return sev === 'info' ? '' : 'var(--gamma-warn)';
}

/** A node's config as display rows, for the inspector summary / hover tooltip. */
export function nodeConfigEntries(n: AuthoredNode): { k: string; v: string }[] {
    return Object.entries(n.config ?? {}).map(([k, v]) => ({
        k,
        v: typeof v === 'string' ? v : JSON.stringify(v),
    }));
}

// ── Authored-model reducers (pure) + the canvas edge-id codec ──
//
// The G6 host has no concept of an edge's semantic identity, so the editor encodes
// `<from>-><to>:<rel>:<nonce>` as the canvas edge id and decodes it back to look up/mutate the
// authored model. These reducers return a NEW `AuthoredPipeline` (or `null` for a no-op, e.g. a
// duplicate edge) — the editor component only owns applying the result + syncing the canvas.

/** Monotonic tail for {@link encodeEdgeId} — `Date.now()` alone can collide when two edges are
 *  encoded in the same millisecond (e.g. programmatic bulk-add), so a counter guarantees uniqueness. */
let edgeIdSeq = 0;

/** Encode a canvas edge id for one `(from, to, rel)` triple (nonce keeps ids unique after a re-label). */
export function encodeEdgeId(from: string, to: string, rel: string): string {
    return `${from}->${to}:${rel}:${Date.now()}-${++edgeIdSeq}`;
}

/** Decode a canvas edge id back to its `(from, to, rel)` triple, or `null` if malformed. */
export function decodeEdgeId(g6EdgeId: string): { from: string; to: string; rel: string } | null {
    const m = /^(.*)->(.*):([^:]*):[^:]*$/.exec(g6EdgeId);
    return m ? { from: m[1], to: m[2], rel: m[3] } : null;
}

/** A fresh node id for `type`: its sanitized type name, deduplicated against the model's existing ids. */
export function uniqueNodeId(model: AuthoredPipeline | null, type: string): string {
    const base = type.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'node';
    const ids = new Set(model?.nodes.map((n) => n.id));
    let i = 1;
    let id = `${base}_${i}`;
    while (ids.has(id)) id = `${base}_${++i}`;
    return id;
}

/** Append a node. */
export function addNodeToModel(model: AuthoredPipeline, node: AuthoredNode): AuthoredPipeline {
    return { ...model, nodes: [...model.nodes, node] };
}

/** Append an edge, or `null` if an identical `(from, to, rel)` edge already exists. */
export function addEdgeToModel(model: AuthoredPipeline, from: string, to: string, rel: string): AuthoredPipeline | null {
    if (model.edges.some((e) => e.from === from && e.to === to && e.rel === rel)) return null;
    return { ...model, edges: [...model.edges, { from, rel, to }] };
}

/** Drop a node and every edge touching it. */
export function removeNodeFromModel(model: AuthoredPipeline, id: string): AuthoredPipeline {
    return {
        ...model,
        nodes: model.nodes.filter((n) => n.id !== id),
        edges: model.edges.filter((e) => e.from !== id && e.to !== id),
    };
}

/** Drop one `(from, to, rel)` edge. */
export function removeEdgeFromModel(model: AuthoredPipeline, from: string, to: string, rel: string): AuthoredPipeline {
    return { ...model, edges: model.edges.filter((e) => !(e.from === from && e.to === to && e.rel === rel)) };
}

/** Re-label an edge's relationship, or `null` if unchanged / would collide with an existing edge. */
export function setEdgeRelInModel(
    model: AuthoredPipeline,
    from: string,
    to: string,
    oldRel: string,
    newRel: string,
): AuthoredPipeline | null {
    if (oldRel === newRel) return null;
    if (model.edges.some((e) => e.from === from && e.to === to && e.rel === newRel)) return null;
    return {
        ...model,
        edges: model.edges.map((e) => (e.from === from && e.to === to && e.rel === oldRel ? { ...e, rel: newRel } : e)),
    };
}

/** Replace a node in the model with its edited version (by id). */
export function applyNodePatchInModel(model: AuthoredPipeline, updated: AuthoredNode): AuthoredPipeline {
    return { ...model, nodes: model.nodes.map((n) => (n.id === updated.id ? updated : n)) };
}

/** Relationships a canvas edge may carry: the source node's emitted rels, `data`, and the edge's current rel. */
export function candidateRelsFor(
    model: AuthoredPipeline | null,
    g6EdgeId: string,
    typeEmits: ReadonlyMap<string, string[]>,
): string[] {
    const p = decodeEdgeId(g6EdgeId);
    if (!p) return [];
    const src = model?.nodes.find((n) => n.id === p.from);
    const emits = src ? (typeEmits.get(src.type) ?? []) : [];
    return [...new Set(['data', ...emits, p.rel])];
}
