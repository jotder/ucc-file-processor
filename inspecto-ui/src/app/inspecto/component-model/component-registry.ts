import { ComponentKind } from './component-kind';

/**
 * Module-level registry of {@link ComponentKind}s, populated once at app start (the same lifecycle as the
 * planned `viz-registry` and `grid`'s ModuleRegistry side-effect registration).
 */
const KINDS = new Map<string, ComponentKind>();

/**
 * Register a kind. Generic in the config shape so kinds with a concrete `config` type (e.g. a `DatasetConfig`,
 * a chart's config) register without a cast; stored under the base type. Throws on a duplicate id so
 * collisions surface at startup, not silently.
 */
export function registerKind<C>(kind: ComponentKind<C>): void {
    if (KINDS.has(kind.id)) {
        throw new Error(`Duplicate ComponentKind '${kind.id}'`);
    }
    KINDS.set(kind.id, kind as ComponentKind);
}

export function getKind(id: string): ComponentKind | undefined {
    return KINDS.get(id);
}

export function allKinds(): ComponentKind[] {
    return [...KINDS.values()];
}

/**
 * Test-only: empty the registry for an isolated spec, returning a function that restores the prior
 * contents. Spec files share a per-worker module graph and kinds register via one-shot module side
 * effects, so a bare clear would starve every later spec file in the worker — clearing without
 * restoring is deliberately not offered.
 */
export function isolateKinds(): () => void {
    const saved = [...KINDS.values()];
    KINDS.clear();
    return () => {
        KINDS.clear();
        for (const k of saved) KINDS.set(k.id, k);
    };
}
