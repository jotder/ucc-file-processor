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
 * Which composition strategy a kind authors. Only the variants a real kind consumes are defined; `schedule`
 * (job) is added when that kind lands (see the adoption plan's STOP). `layout` arrived with P2 (dashboard).
 */
export type WiringStrategy = 'none' | 'graph' | 'mapping' | 'layout';

/** Kind-specific topology over a composite's parts. */
export type Wiring =
    | { strategy: 'none' }
    | { strategy: 'graph'; nodes: WiringNode[]; edges: WiringEdge[] } // pipeline / flow DAG
    | { strategy: 'mapping'; channels: Record<string, string> } // chart field → channel
    | { strategy: 'layout'; tiles: LayoutTile[] }; // dashboard grid

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
