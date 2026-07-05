import { getViz, registerViz } from '../viz-registry';
import { VizPlugin } from '../viz-types';
import { AREA_PLUGIN, BAR_PLUGIN, LINE_PLUGIN, PIE_PLUGIN } from './standard.plugins';
import { TABLE_PLUGIN } from './table.plugin';
import { KPI_PLUGIN } from './kpi.plugin';
import { BUBBLE_PLUGIN } from './bubble.plugin';
import { GAUGE_PLUGIN } from './gauge.plugin';
import { SCATTER_PLUGIN } from './scatter.plugin';
import { FUNNEL_PLUGIN } from './funnel.plugin';
import { GEO_MAP_PLUGIN, LINK_ANALYSIS_PLUGIN } from './view.plugins';

/** The plugin set. KPI + table first (always-available), then the Chart.js standards, then the P3 breadth
 *  additions (bubble, gauge, scatter, funnel), then the view-bound investigation plugins (Phase 4). */
export const BUILTIN_VIZ_PLUGINS: VizPlugin[] = [
    KPI_PLUGIN,
    TABLE_PLUGIN,
    BAR_PLUGIN,
    LINE_PLUGIN,
    AREA_PLUGIN,
    PIE_PLUGIN,
    BUBBLE_PLUGIN,
    GAUGE_PLUGIN,
    SCATTER_PLUGIN,
    FUNNEL_PLUGIN,
    GEO_MAP_PLUGIN,
    LINK_ANALYSIS_PLUGIN,
];

/**
 * Register the built-in plugins once. Guarded (skips already-registered ids) so a repeated side-effect import
 * — or a spec that imports this module after others — doesn't trip the registry's dup guard.
 */
export function registerBuiltinViz(): void {
    for (const p of BUILTIN_VIZ_PLUGINS) {
        if (!getViz(p.meta.type)) registerViz(p);
    }
}

// Side-effect: register on import (Studio loads this when the widget kind is pulled in).
registerBuiltinViz();

export { AREA_PLUGIN, BAR_PLUGIN, LINE_PLUGIN, PIE_PLUGIN } from './standard.plugins';
export { TABLE_PLUGIN } from './table.plugin';
export { KPI_PLUGIN } from './kpi.plugin';
export { BUBBLE_PLUGIN } from './bubble.plugin';
export { GAUGE_PLUGIN } from './gauge.plugin';
export { SCATTER_PLUGIN } from './scatter.plugin';
export { FUNNEL_PLUGIN } from './funnel.plugin';
export { GEO_MAP_PLUGIN, LINK_ANALYSIS_PLUGIN } from './view.plugins';
