import { Ref } from './component-types';
import { getKind } from './component-registry';

/**
 * R1 of the living-operational-system roadmap: the ONE derivation of "what does this config
 * reference?" — the metadata network's edges. Previously four sites re-derived this with different
 * completeness (reuse-graph `partsFor`, bundle `refsOf`, mock integrity rules, `dashboardParts`);
 * they all now come through {@link refsForComponent}.
 *
 * Structural: the functions read config shapes as plain maps (no feature imports), so shared-layer
 * consumers (mock integrity) and pre-registration consumers work identically. A registered
 * {@link ComponentKind.deriveRefs} takes precedence — the seam future kinds implement.
 */

/** Split a node's `use: '<kind>/<id>'` binding. The `connections` prefix maps to the `connection` kind. */
export function parseUseRef(use: string | undefined): { kind: string; id: string } | null {
    const trimmed = use?.trim();
    if (!trimmed) return null;
    const i = trimmed.indexOf('/');
    if (i <= 0 || i === trimmed.length - 1) return null;
    const prefix = trimmed.slice(0, i);
    return { kind: prefix === 'connections' ? 'connection' : prefix, id: trimmed.slice(i + 1) };
}

/** A widget binds a dataset (or, when query-bound, a saved query), or (view-bound, by vizType) renders a
 *  saved investigation view. */
export function widgetRefs(config: Record<string, unknown>): Ref[] {
    const refs: Ref[] = [];
    const queryId = config['queryId'] as string | undefined;
    if (queryId) refs.push({ kind: 'query', id: queryId, rel: 'binds', via: 'query' });
    const datasetId = config['datasetId'] as string | undefined;
    if (datasetId) refs.push({ kind: 'dataset', id: datasetId, rel: 'binds', via: 'dataset' });
    const viewId = config['viewId'] as string | undefined;
    const vizType = config['vizType'] as string | undefined;
    if (viewId && vizType === 'geo-map') refs.push({ kind: 'geo-map-view', id: viewId, rel: 'renders', via: 'view' });
    if (viewId && vizType === 'link-analysis') refs.push({ kind: 'link-analysis-view', id: viewId, rel: 'renders', via: 'view' });
    return refs;
}

/** A dashboard tiles widgets — one edge per tile, anchored `tile<i>` (matches the layout wiring ids). */
export function dashboardRefs(config: Record<string, unknown>): Ref[] {
    const tiles = (config['tiles'] as { widgetId?: string }[] | undefined) ?? [];
    return tiles.flatMap((t, i) => (t?.widgetId ? [{ kind: 'widget', id: t.widgetId, rel: 'tiles' as const, via: `tile${i}` }] : []));
}

/** A saved investigation view projects dataset(s) — every `datasetId` inside its query config. */
export function investigationViewRefs(config: Record<string, unknown>): Ref[] {
    const query = (config['query'] as Record<string, unknown> | undefined) ?? {};
    const refs: Ref[] = [];
    for (const [part, value] of Object.entries(query)) {
        const datasetId = (value as Record<string, unknown> | undefined)?.['datasetId'] as string | undefined;
        if (datasetId) refs.push({ kind: 'dataset', id: datasetId, rel: 'projects', via: part });
    }
    return refs;
}

/** A job triggers on an upstream pipeline's events (`onPipeline`). `params` stay opaque until the R3
 *  parameter namespace gives them declarable references. */
export function jobRefs(config: Record<string, unknown>): Ref[] {
    const onPipeline = config['onPipeline'] as string | undefined | null;
    return onPipeline ? [{ kind: 'pipeline', id: onPipeline, rel: 'triggers', via: 'onPipeline' }] : [];
}

/** A query binds its source dataset (R3) — the `widget → query → dataset` lineage chain's middle edge. */
export function queryRefs(config: Record<string, unknown>): Ref[] {
    const datasetId = config['datasetId'] as string | undefined | null;
    return datasetId ? [{ kind: 'dataset', id: datasetId, rel: 'binds', via: 'dataset' }] : [];
}

/**
 * A decision rule (R5) `binds` its target pipeline/job and `invokes` the components its platform
 * consequences act on (start-job → job, trigger-pipeline → pipeline, render-widget → widget). Read as
 * plain maps (no feature import) so this stays a leaf like the other derivations.
 */
export function decisionRuleRefs(config: Record<string, unknown>): Ref[] {
    const refs: Ref[] = [];
    const targetType = config['targetType'] as string | undefined;
    const target = config['target'] as string | undefined;
    if (target && (targetType === 'pipeline' || targetType === 'job')) {
        refs.push({ kind: targetType, id: target, rel: 'binds', via: 'target' });
    }
    const consequences = (config['consequences'] as { action?: string; target?: { kind?: string; id?: string } }[] | undefined) ?? [];
    consequences.forEach((c, i) => {
        const t = c?.target;
        if (t?.id && t?.kind) refs.push({ kind: t.kind, id: t.id, rel: 'invokes', via: `consequence${i}` });
    });
    return refs;
}

/** A pipeline's nodes bind registry components / connections via `use:` — anchored on the node id. */
export function pipelineRefs(config: Record<string, unknown>): Ref[] {
    const nodes = (config['nodes'] as { id?: string; use?: string }[] | undefined) ?? [];
    return nodes.flatMap((n) => {
        const ref = parseUseRef(n?.use);
        return ref ? [{ ...ref, rel: 'binds' as const, via: n.id }] : [];
    });
}

/** One `<kind>/<id>` token (no spaces, single slash) — the shape a delivered-note ref must have. */
const DELIVERED_REF_RE = /^[A-Za-z][A-Za-z0-9-]*\/[^\s/]+$/;

/**
 * A requirement is `delivered-by` the component(s) its delivered-note names (C1 follow-up: the note
 * doubles as component ref(s) when the deliver picker filled it). Edges derive only when the whole
 * note is a pure ref list — `<kind>/<id>` tokens separated by whitespace, `+` or `,` — so prose
 * ("delivered in Q3 via pipelines/orders rework") never becomes an edge.
 */
export function requirementRefs(config: Record<string, unknown>): Ref[] {
    const note = (config['deliveredNote'] as string | undefined)?.trim();
    if (!note) return [];
    const tokens = note.split(/[\s,+]+/).filter(Boolean);
    if (!tokens.length || !tokens.every((t) => DELIVERED_REF_RE.test(t))) return [];
    return tokens.flatMap((t, i) => {
        const ref = parseUseRef(t);
        return ref ? [{ ...ref, rel: 'delivered-by' as const, via: `deliveredNote${i}` }] : [];
    });
}

/** Kinds with a structural derivation. `authored-pipeline` (the store name) aliases the `pipeline` kind. */
const STRUCTURAL: Record<string, (config: Record<string, unknown>) => Ref[]> = {
    widget: widgetRefs,
    dashboard: dashboardRefs,
    query: queryRefs,
    'geo-map-view': investigationViewRefs,
    'link-analysis-view': investigationViewRefs,
    pipeline: pipelineRefs,
    'authored-pipeline': pipelineRefs,
    job: jobRefs,
    'decision-rule': decisionRuleRefs,
    requirement: requirementRefs,
};

/**
 * The outgoing lineage edges of a component config. Order of authority: the registered kind's
 * `deriveRefs` (the seam) → the structural derivation → none (atomic kinds reference nothing).
 */
export function refsForComponent(kind: string, config: Record<string, unknown>): Ref[] {
    const registered = getKind(kind === 'authored-pipeline' ? 'pipeline' : kind) ?? getKind(kind);
    if (registered?.deriveRefs) return registered.deriveRefs(config);
    return STRUCTURAL[kind]?.(config) ?? [];
}
