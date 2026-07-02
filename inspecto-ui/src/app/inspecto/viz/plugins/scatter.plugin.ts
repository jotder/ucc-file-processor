import { channelMeasure, channelMeasureId } from '../query-spec';
import { ControlSpec, ControlValues, QuerySpec, VizPlugin, VizProps } from '../viz-types';
import { QueryCtx } from './plugin-helpers';

/**
 * Scatter plugin — two measures (x/y) grouped by one dimension; each group is a point. Same parallel-series
 * encoding as bubble (series 'x' + 'y'), zipped into Chart.js `{x,y}` points by the render host's scatter
 * branch. The correlation view bubble covers only when a third (size) measure exists.
 */
const CONTROLS: ControlSpec[] = [
    { channel: 'series', label: 'Label by', acceptRoles: ['dimension', 'temporal'], required: true },
    { channel: 'x', label: 'X measure', acceptRoles: ['measure'], isMeasure: true, required: true },
    { channel: 'y', label: 'Y measure', acceptRoles: ['measure'], isMeasure: true, required: true },
];

function buildScatterQuery(values: ControlValues, ctx: QueryCtx): QuerySpec {
    const groupBy = values.series?.[0]?.field ? [values.series[0].field] : [];
    const measures = (['x', 'y'] as const)
        .map((ch) => values[ch]?.[0])
        .filter((cv): cv is NonNullable<typeof cv> => !!cv)
        .map((cv) => channelMeasure({ ...cv, agg: cv.agg ?? 'avg' }));
    return { datasetId: ctx.datasetId, sourceName: ctx.sourceName, groupBy, measures, filters: ctx.filters ?? null };
}

function transformScatter(rows: Record<string, unknown>[], values: ControlValues): VizProps {
    const labelField = values.series?.[0]?.field;
    const xCv = values.x?.[0];
    const yCv = values.y?.[0];
    if (!labelField || !xCv || !yCv) return { labels: [], series: [] };
    const xId = channelMeasureId({ ...xCv, agg: xCv.agg ?? 'avg' });
    const yId = channelMeasureId({ ...yCv, agg: yCv.agg ?? 'avg' });
    const num = (v: unknown): number => (typeof v === 'number' ? v : Number(v) || 0);
    return {
        labels: rows.map((r) => String(r[labelField] ?? '')),
        series: [
            { label: 'x', data: rows.map((r) => num(r[xId])) },
            { label: 'y', data: rows.map((r) => num(r[yId])) },
        ],
    };
}

export const SCATTER_PLUGIN: VizPlugin = {
    meta: { type: 'scatter', label: 'Scatter', icon: 'heroicons_outline:cursor-arrow-ripple', fit: { minMeasure: 2, minDim: 1 } },
    controls: CONTROLS,
    buildQuery: buildScatterQuery,
    transformProps: transformScatter,
    render: { kind: 'chartjs', chartType: 'scatter' },
};
