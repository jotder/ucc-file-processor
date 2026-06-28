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

/** Test-only: reset the registry between specs. */
export function clearKinds(): void {
    KINDS.clear();
}
