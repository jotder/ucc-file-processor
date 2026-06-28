import { ComponentKind } from './component-kind';

/**
 * Module-level registry of {@link ComponentKind}s, populated once at app start (the same lifecycle as the
 * planned `viz-registry` and `grid`'s ModuleRegistry side-effect registration).
 */
const KINDS = new Map<string, ComponentKind>();

/** Register a kind. Throws on a duplicate id so collisions surface at startup, not silently. */
export function registerKind(kind: ComponentKind): void {
    if (KINDS.has(kind.id)) {
        throw new Error(`Duplicate ComponentKind '${kind.id}'`);
    }
    KINDS.set(kind.id, kind);
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
