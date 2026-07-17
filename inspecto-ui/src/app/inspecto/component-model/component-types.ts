/**
 * The unified component metamodel. Every reusable artifact in the platform — grammar, schema, transform,
 * sink, connection, rule, dataset, widget, kpi, dashboard, pipeline, job — is a {@link Component}: a `kind`
 * + `config`, optionally composed of `parts` wired in a kind-specific fashion. Atomic = no parts/wiring;
 * composite = parts + a {@link Wiring}. See docs/superpower/component-model.md.
 *
 * Framework-agnostic (no Angular) so it unit-tests in plain vitest, like the query core.
 */

/** A reusable artifact. `C` is the kind-specific config shape (a QueryModel for `rule`, a dialect map for `grammar`, …). */
export interface Component<C = Record<string, unknown>> {
    kind: string; // ComponentKind id
    id: string;
    name: string;
    space?: string; // multi-space scope (mirrors SpacesService.currentSpaceId); optional
    config: C;
    parts?: Part[]; // present ⇒ composite
    wiring?: Wiring; // how the parts connect
}

/** A reference to another component, with a local config override merged over the referent's own config. */
export interface Part {
    partId: string; // stable id within the parent (a flow nodeId, a dashboard tile id)
    ref: ComponentRef;
    configOverride?: Record<string, unknown>;
}

/** A `<kind>/<id>` pointer, or an inline (unsaved / embedded) child component. */
export interface ComponentRef {
    kind: string;
    id?: string; // resolvable via the registry / DataProvider; absent ⇒ inline-only
    inline?: Component; // an embedded child (e.g. a virtual dataset inside a chart)
}

/**
 * Edge semantics of the metadata network (living-operational-system.md §2): `binds` = config
 * references another component (widget→dataset, pipeline-node→grammar/connection) · `tiles` =
 * dashboard→widget placement · `renders` = view-bound widget→saved view · `projects` = saved
 * view→dataset · `triggers` = job→pipeline event trigger (the Signal network's first lineage edge)
 * · `loads` = dataset→physical store (reserved; no producer yet) · `emits` = the producer that
 * raised a Signal (`Signal.source`, R4 — the Signal network's runtime edge into the metadata graph)
 * · `invokes` = a decision Consequence targets a component it acts on (R5) · `delivered-by` = a
 * Requirement points at the component that satisfied it (C1 follow-up; derives only from an exact
 * `<kind>/<id>` delivered-note).
 */
export type RefRel = 'binds' | 'tiles' | 'renders' | 'projects' | 'triggers' | 'loads' | 'emits' | 'invokes' | 'delivered-by';

/**
 * One outgoing lineage edge derivable from a component's config — THE unit of the metadata
 * network. `via` anchors the edge locally (a flow node id, `tile3`, `dataset`) so consumers that
 * need part identity (reuse-graph, wiring) keep stable ids; graph-only consumers ignore it.
 */
export interface Ref {
    kind: string;
    id: string;
    rel: RefRel;
    via?: string;
}

/**
 * Which composition strategy a kind authors. Only the variants a real kind consumes are defined; `layout`
 * arrived with P2 (dashboard), `schedule` with R2 (job — the trigger/scheduler made first-class as the
 * job's wiring rather than separate kinds; living-operational-system.md §5).
 */
export type WiringStrategy = 'none' | 'graph' | 'mapping' | 'layout' | 'schedule';

/** Kind-specific topology over a composite's parts. */
export type Wiring =
    | { strategy: 'none' }
    | { strategy: 'graph'; nodes: WiringNode[]; edges: WiringEdge[] } // pipeline / flow DAG
    | { strategy: 'mapping'; channels: Record<string, string> } // chart field → channel
    | { strategy: 'layout'; tiles: LayoutTile[] } // dashboard grid
    | { strategy: 'schedule'; cron?: string; on?: string }; // job trigger: a cron, an upstream pipeline event, or neither (manual)

export interface WiringNode {
    partId: string;
}

/** One placed tile in a dashboard's grid wiring — which part, and how wide (column span). */
export interface LayoutTile {
    partId: string;
    w: number;
}

/** A directed wire between two parts. `rel` is the semantic relationship; `kind` the flow class. */
export interface WiringEdge {
    from: string; // partId
    to: string; // partId
    rel: string;
    kind?: 'data' | 'control' | 'route';
}
