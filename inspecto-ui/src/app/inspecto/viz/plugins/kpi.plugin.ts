import { VizPlugin } from '../viz-types';
import { buildValueQuery, transformValue } from './plugin-helpers';

/**
 * KPI plugin — a single headline value (no dimensions). Renders via the component escape hatch
 * (`render.kind:'component'`, key `kpi`) so the Angular {@link KpiComponent} can toggle its 3 in-place modes
 * (mini value → standard trend → max). Aligns with the server's declarative `NodeKind.KPI`.
 */
export const KPI_PLUGIN: VizPlugin = {
    meta: { type: 'kpi', label: 'KPI', icon: 'heroicons_outline:variable', fit: { minMeasure: 1, maxDim: 0 } },
    controls: [{ channel: 'value', label: 'Value', acceptRoles: ['measure'], isMeasure: true, required: true }],
    buildQuery: buildValueQuery,
    transformProps: transformValue,
    render: { kind: 'component', componentKey: 'kpi' },
};
