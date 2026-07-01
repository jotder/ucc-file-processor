import { Part, Wiring, WiringStrategy } from './component-types';

/** A finding from a kind's config validation — returned (never thrown) so the UI can surface them all at once. */
export interface ConfigFinding {
    severity: 'error' | 'warning' | 'info';
    path?: string;
    message: string;
}

/**
 * The registry entry for a component kind — its config / wiring / authoring / exec strategy bundle. This is
 * the platform-wide generalization of the Studio `VizPlugin` (which is the `kind:'widget'` entry).
 *
 * The authoring/exec seams are **string keys**, resolved Angular-side via a token map (NgComponentOutlet),
 * so this module imports no Angular and stays vitest-pure.
 */
export interface ComponentKind<C = Record<string, unknown>> {
    id: string;
    label: string;
    /** Atomic kinds declare `[]`; composites list which child kinds a part may reference. */
    allowedPartKinds: string[];
    /** The wiring variant this kind authors. */
    wiring: WiringStrategy;
    /** CONFIG seam — validate a config blob (returns findings, never throws); optional factory for a new instance. */
    config: {
        validate(config: unknown): ConfigFinding[];
        create?(): C;
    };
    /** WIRING seam — derive the typed Wiring from parts (pure). Atomic kinds omit it / return `{strategy:'none'}`. */
    deriveWiring?(parts: Part[], config: C): Wiring;
    /** AUTHORING seam — names the Angular editor to mount (resolved by a host token map; never imported here). */
    authoring?: { editorKey: string };
    /** EXEC seam — names the runtime binding (offline AlaSQL now / backend later). */
    exec?: { runnerKey: string };
}
