import { AuthoredFlow } from 'app/inspecto/api';
import { ComponentKind, Part, Wiring, getKind, registerKind } from 'app/inspecto/component-model';

/**
 * P2 of the component-model adoption plan: register the platform's **existing** kinds on the unified registry
 * as thin, register-only adapters (no UI / behavior change). Atomic kinds (grammar / schema / transform /
 * sink / rule) are leaves; `pipeline` is the one composite — its `graph` wiring **is** the authored flow DAG
 * (the north-star "FlowGraph is literally a pipeline's wiring"). Studio's chart / dataset / dashboard register
 * separately (their own `*.kind.ts`).
 *
 * Validators are intentionally tiny / no-op (no JSON-schema engine — adoption-plan STOP): they exist so the
 * registry can surface a kind's identity + part rules to the reuse-graph (P3), not to re-validate what the
 * per-kind editors and the backend already enforce.
 */

/** An atomic registry kind: no parts, no wiring, a no-op validator (the editors / backend own real validation). */
function atomicKind(id: string, label: string): ComponentKind {
    return { id, label, allowedPartKinds: [], wiring: 'none', config: { validate: () => [] } };
}

// User-facing labels mirror the flow palette taxonomy (PARSE → Parser, TRANSFORM → Transformer, SINK → Writer).
export const GRAMMAR_KIND = atomicKind('grammar', 'Parser');
export const SCHEMA_KIND = atomicKind('schema', 'Schema');
export const TRANSFORM_KIND = atomicKind('transform', 'Transformer');
export const SINK_KIND = atomicKind('sink', 'Writer');
export const RULE_KIND = atomicKind('rule', 'Rule');

const ATOMIC_KINDS: ComponentKind[] = [GRAMMAR_KIND, SCHEMA_KIND, TRANSFORM_KIND, SINK_KIND, RULE_KIND];

/**
 * The `pipeline` composite kind. Its parts are the registry components its nodes bind (parser / transform /
 * sink …); its `graph` wiring is the authored flow's DAG — `deriveWiring` maps the {@link AuthoredFlow} edges
 * onto {@link Wiring} edges (the parts supply the nodes). Authoring stays the existing flow editor; exec is the
 * backend pipeline runner. (Deriving the *parts* from a flow needs the palette's type→kind map and lands with
 * the P3 reuse-graph, where that catalog is in hand.)
 */
export const PIPELINE_KIND: ComponentKind<AuthoredFlow> = {
    id: 'pipeline',
    label: 'Pipeline',
    allowedPartKinds: ['grammar', 'schema', 'transform', 'sink'],
    wiring: 'graph',
    config: { validate: () => [] },
    deriveWiring: (parts: Part[], flow: AuthoredFlow): Wiring => ({
        strategy: 'graph',
        nodes: parts.map((p) => ({ partId: p.partId })),
        edges: (flow?.edges ?? []).map((e) => ({ from: e.from, to: e.to, rel: e.rel })),
    }),
    authoring: { editorKey: 'pipeline' },
    exec: { runnerKey: 'pipeline' },
};

/** Every kind id P2 registers (atomic + pipeline) — Studio's chart / dataset / dashboard register separately. */
export const PLATFORM_KIND_IDS: string[] = [...ATOMIC_KINDS.map((k) => k.id), PIPELINE_KIND.id];

/**
 * Register the platform kinds once. Guarded (skips already-registered ids) so a repeated side-effect import —
 * or a spec — never trips the registry's duplicate-id throw. Wired into the app when the P3 registry route
 * loads (the same side-effect-on-route-load pattern Studio uses for its kinds).
 */
export function registerPlatformKinds(): void {
    for (const k of ATOMIC_KINDS) if (!getKind(k.id)) registerKind(k);
    if (!getKind(PIPELINE_KIND.id)) registerKind<AuthoredFlow>(PIPELINE_KIND);
}
