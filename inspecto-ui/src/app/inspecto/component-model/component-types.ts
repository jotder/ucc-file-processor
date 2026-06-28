/**
 * The unified component metamodel. Every reusable artifact in the platform — grammar, schema, transform,
 * sink, connection, rule, dataset, chart, kpi, dashboard, pipeline, job — is a {@link Component}: a `kind`
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
 * Which composition strategy a kind authors. Only the variants a real kind consumes are defined; `layout`
 * (dashboard grid) and `schedule` (job) are added when those kinds land (see the adoption plan's STOP).
 */
export type WiringStrategy = 'none' | 'graph' | 'mapping';

/** Kind-specific topology over a composite's parts. */
export type Wiring =
    | { strategy: 'none' }
    | { strategy: 'graph'; nodes: WiringNode[]; edges: WiringEdge[] } // pipeline / flow DAG
    | { strategy: 'mapping'; channels: Record<string, string> }; // chart field → channel

export interface WiringNode {
    partId: string;
}

/** A directed wire between two parts. `rel` is the semantic relationship; `kind` the flow class. */
export interface WiringEdge {
    from: string; // partId
    to: string; // partId
    rel: string;
    kind?: 'data' | 'control' | 'route';
}
