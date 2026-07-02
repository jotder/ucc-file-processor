import { ControlSpec, VizPlugin } from '../viz-types';
import { buildXyQuery, transformXy } from './plugin-helpers';

/**
 * Funnel plugin — one measure over one dimension's stages, drawn as a horizontal bar chart sorted largest →
 * smallest (the render host flips `indexAxis` for `meta.type:'funnel'`). A Chart.js-native approximation like
 * gauge — no new charting dependency.
 */
const CONTROLS: ControlSpec[] = [
    { channel: 'x', label: 'Stage', acceptRoles: ['dimension'], required: true },
    { channel: 'y', label: 'Measure', acceptRoles: ['measure'], isMeasure: true, required: true },
];

export const FUNNEL_PLUGIN: VizPlugin = {
    meta: {
        type: 'funnel',
        label: 'Funnel',
        icon: 'heroicons_outline:funnel',
        fit: { minMeasure: 1, minDim: 1, maxDim: 1, temporal: false, maxCardinality: 10 },
    },
    controls: CONTROLS,
    buildQuery: buildXyQuery,
    transformProps: (rows, values) => {
        const props = transformXy(rows, values);
        // Funnel reads top-down largest-first: sort categories by the measure, descending.
        const order = props.labels
            .map((_, i) => i)
            .sort((a, b) => (props.series[0]?.data[b] ?? 0) - (props.series[0]?.data[a] ?? 0));
        return {
            ...props,
            labels: order.map((i) => props.labels[i]),
            series: props.series.map((s) => ({ ...s, data: order.map((i) => s.data[i]) })),
        };
    },
    render: { kind: 'chartjs', chartType: 'bar' },
};
