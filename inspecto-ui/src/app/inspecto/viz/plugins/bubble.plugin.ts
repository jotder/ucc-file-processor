import { buildMeasure, measureId } from '../query-spec';
import { ControlSpec, ControlValues, QuerySpec, VizPlugin, VizProps } from '../viz-types';
import { QueryCtx } from './plugin-helpers';

/**
 * Bubble plugin — three measures (x/y/size) grouped by one dimension, so each group becomes a point. Stored
 * as three parallel {@link VizProps.series} arrays (x, y, size) rather than a new props shape — the render
 * host (`VizRenderComponent.chartData`) zips them into Chart.js `{x,y,r}` points for `chartType:'bubble'`.
 */
const CONTROLS: ControlSpec[] = [
    { channel: 'series', label: 'Label by', acceptRoles: ['dimension', 'temporal'], required: true },
    { channel: 'x', label: 'X measure', acceptRoles: ['measure'], isMeasure: true, required: true },
    { channel: 'y', label: 'Y measure', acceptRoles: ['measure'], isMeasure: true, required: true },
    { channel: 'size', label: 'Size measure', acceptRoles: ['measure'], isMeasure: true, required: true },
];

function buildBubbleQuery(values: ControlValues, ctx: QueryCtx): QuerySpec {
    const groupBy = values.series?.[0]?.field ? [values.series[0].field] : [];
    const measures = (['x', 'y', 'size'] as const)
        .map((ch) => values[ch]?.[0])
        .filter((cv): cv is NonNullable<typeof cv> => !!cv)
        .map((cv) => buildMeasure(cv.agg ?? 'avg', cv.field));
    return { datasetId: ctx.datasetId, sourceName: ctx.sourceName, groupBy, measures, filters: ctx.filters ?? null };
}

function transformBubble(rows: Record<string, unknown>[], values: ControlValues): VizProps {
    const labelField = values.series?.[0]?.field;
    const xCv = values.x?.[0];
    const yCv = values.y?.[0];
    const sizeCv = values.size?.[0];
    if (!labelField || !xCv || !yCv || !sizeCv) return { labels: [], series: [] };
    const xId = measureId(xCv.agg ?? 'avg', xCv.field);
    const yId = measureId(yCv.agg ?? 'avg', yCv.field);
    const sizeId = measureId(sizeCv.agg ?? 'avg', sizeCv.field);
    const num = (v: unknown): number => (typeof v === 'number' ? v : Number(v) || 0);
    const labels = rows.map((r) => String(r[labelField] ?? ''));
    return {
        labels,
        series: [
            { label: 'x', data: rows.map((r) => num(r[xId])) },
            { label: 'y', data: rows.map((r) => num(r[yId])) },
            { label: 'size', data: rows.map((r) => num(r[sizeId])) },
        ],
    };
}

export const BUBBLE_PLUGIN: VizPlugin = {
    meta: { type: 'bubble', label: 'Bubble', icon: 'heroicons_outline:variable', fit: { minMeasure: 3, minDim: 1 } },
    controls: CONTROLS,
    buildQuery: buildBubbleQuery,
    transformProps: transformBubble,
    render: { kind: 'chartjs', chartType: 'bubble' },
};
