import { getViz, registerViz } from '../viz-registry';
import { VizPlugin } from '../viz-types';
import { AREA_PLUGIN, BAR_PLUGIN, LINE_PLUGIN, PIE_PLUGIN } from './standard.plugins';
import { TABLE_PLUGIN } from './table.plugin';
import { KPI_PLUGIN } from './kpi.plugin';

/** The P1 plugin set. KPI + table first (always-available), then the Chart.js standards. */
export const BUILTIN_VIZ_PLUGINS: VizPlugin[] = [KPI_PLUGIN, TABLE_PLUGIN, BAR_PLUGIN, LINE_PLUGIN, AREA_PLUGIN, PIE_PLUGIN];

/**
 * Register the built-in plugins once. Guarded (skips already-registered ids) so a repeated side-effect import
 * — or a spec that imports this module after others — doesn't trip the registry's dup guard.
 */
export function registerBuiltinViz(): void {
    for (const p of BUILTIN_VIZ_PLUGINS) {
        if (!getViz(p.meta.type)) registerViz(p);
    }
}

// Side-effect: register on import (Studio loads this when the chart kind is pulled in).
registerBuiltinViz();

export { AREA_PLUGIN, BAR_PLUGIN, LINE_PLUGIN, PIE_PLUGIN } from './standard.plugins';
export { TABLE_PLUGIN } from './table.plugin';
export { KPI_PLUGIN } from './kpi.plugin';
