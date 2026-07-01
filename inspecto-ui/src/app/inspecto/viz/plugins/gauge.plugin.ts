import { VizPlugin } from '../viz-types';
import { buildValueQuery, transformValue } from './plugin-helpers';

/**
 * Gauge plugin — a single headline measure rendered as a half-circle doughnut (`VizRenderComponent` styles
 * the `gauge`-typed plugin's Chart.js config: 180° circumference, a wide cutout, value-vs-remainder slices).
 * Shares its query/transform with {@link KPI_PLUGIN} — same shape (one ungrouped measure), different render.
 * The value is assumed to already be a 0–100 ratio/percentage; there's no separate "max" control in this pass.
 */
export const GAUGE_PLUGIN: VizPlugin = {
    meta: { type: 'gauge', label: 'Gauge', icon: 'heroicons_outline:chart-pie', fit: { minMeasure: 1, maxDim: 0 } },
    controls: [{ channel: 'value', label: 'Value (0–100)', acceptRoles: ['measure'], isMeasure: true, required: true }],
    buildQuery: buildValueQuery,
    transformProps: transformValue,
    render: { kind: 'chartjs', chartType: 'doughnut' },
};
