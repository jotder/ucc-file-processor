import { ControlSpec, VizFit, VizPlugin } from '../viz-types';
import { buildXyQuery, transformXy } from './plugin-helpers';

/**
 * The Chart.js-rendered standard plugins (line / bar / area / pie). All share the x→measure→break-down
 * mapping and the {@link transformXy} pivot; they differ only in `meta.fit` (what the Show-Me recommender
 * prefers) and the Chart.js `chartType`. Area is a filled line.
 */

const XY_CONTROLS: ControlSpec[] = [
    { channel: 'x', label: 'X axis', acceptRoles: ['temporal', 'dimension'], required: true },
    { channel: 'y', label: 'Measure', acceptRoles: ['measure'], isMeasure: true, required: true },
    { channel: 'series', label: 'Break down by', acceptRoles: ['dimension'] },
];

function xyPlugin(type: string, chartType: string, label: string, icon: string, fit: VizFit, fill = false): VizPlugin {
    return {
        meta: { type, label, icon, fit },
        controls: XY_CONTROLS,
        buildQuery: buildXyQuery,
        transformProps: (rows, values) => {
            const props = transformXy(rows, values);
            if (fill) props.series.forEach((s) => (s['fill'] = true));
            return props;
        },
        render: { kind: 'chartjs', chartType },
    };
}

export const LINE_PLUGIN = xyPlugin('line', 'line', 'Line', 'heroicons_outline:presentation-chart-line', {
    minMeasure: 1,
    temporal: true,
});
export const BAR_PLUGIN = xyPlugin('bar', 'bar', 'Bar', 'heroicons_outline:chart-bar', { minMeasure: 1, minDim: 1, maxCardinality: 30 });
export const AREA_PLUGIN = xyPlugin('area', 'line', 'Area', 'heroicons_outline:chart-bar', { minMeasure: 1, temporal: true }, true);

export const PIE_PLUGIN: VizPlugin = {
    meta: {
        type: 'pie',
        label: 'Pie',
        icon: 'heroicons_outline:chart-pie',
        fit: { minMeasure: 1, minDim: 1, maxDim: 1, temporal: false, maxCardinality: 8 },
    },
    controls: [
        { channel: 'x', label: 'Slice by', acceptRoles: ['dimension'], required: true },
        { channel: 'y', label: 'Measure', acceptRoles: ['measure'], isMeasure: true, required: true },
    ],
    buildQuery: buildXyQuery,
    transformProps: transformXy,
    render: { kind: 'chartjs', chartType: 'pie' },
};
