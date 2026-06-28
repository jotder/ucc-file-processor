import { VizPlugin } from './viz-types';

/**
 * Module-level registry of {@link VizPlugin}s (same lifecycle as the component-model `registerKind`). Plugins
 * register via a side-effect import at app start; the `chart` ComponentKind enumerates them as its sub-types.
 */
const PLUGINS = new Map<string, VizPlugin>();

/** Register a plugin. Throws on a duplicate type so collisions surface at startup, not silently. */
export function registerViz(plugin: VizPlugin): void {
    if (PLUGINS.has(plugin.meta.type)) {
        throw new Error(`Duplicate VizPlugin '${plugin.meta.type}'`);
    }
    PLUGINS.set(plugin.meta.type, plugin);
}

export function getViz(type: string): VizPlugin | undefined {
    return PLUGINS.get(type);
}

export function allViz(): VizPlugin[] {
    return [...PLUGINS.values()];
}

/** Test-only: reset the registry between specs. */
export function clearViz(): void {
    PLUGINS.clear();
}
