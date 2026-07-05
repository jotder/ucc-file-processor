import type { Type } from '@angular/core';

/**
 * The component-render loader registry — the Angular-adjacent half of the plugin registry. A plugin whose
 * `render` is `{kind:'component', componentKey}` resolves its component here. Loaders are **async**
 * (`() => import(...)`) so heavy hosts (MapLibre for `geo-map-view`, G6 for `link-analysis-view`) stay out
 * of every eager bundle that imports `viz-render`; features register their loaders as a side effect (the
 * `widget.kind` module), mirroring `registerBuiltinViz`. Only the `Type` *type* is imported — this module
 * stays runtime-framework-free like the rest of the viz core.
 */
export type VizComponentLoader = () => Promise<Type<unknown>>;

const COMPONENT_LOADERS = new Map<string, VizComponentLoader>();

/** Register a componentKey's loader. Throws on a duplicate key — register once, guard with {@link getVizComponentLoader}. */
export function registerVizComponent(key: string, loader: VizComponentLoader): void {
    if (COMPONENT_LOADERS.has(key)) throw new Error(`Duplicate viz component '${key}'`);
    COMPONENT_LOADERS.set(key, loader);
}

export function getVizComponentLoader(key: string): VizComponentLoader | undefined {
    return COMPONENT_LOADERS.get(key);
}
