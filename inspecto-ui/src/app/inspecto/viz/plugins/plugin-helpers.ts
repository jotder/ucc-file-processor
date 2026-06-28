import { ConditionGroup } from 'app/inspecto/query';
import { buildMetric, metricId } from '../query-spec';
import { ChannelValue, ControlValues, QuerySpec, VizProps } from '../viz-types';

/**
 * Shared pure helpers for the standard chart plugins: turn the field mapping into a {@link QuerySpec}, and
 * pivot result rows into Chart.js-ready labels + series. No Angular, no chart.js — just data.
 */

export interface QueryCtx {
    datasetId: string;
    sourceName: string;
    filters?: ConditionGroup | null;
}

function field(cv?: ChannelValue[]): string | undefined {
    return cv?.[0]?.field;
}

function num(v: unknown): number {
    const n = typeof v === 'number' ? v : Number(v);
    return Number.isFinite(n) ? n : 0;
}

function str(v: unknown): string {
    return v == null ? '' : String(v);
}

/** Distinct values of `key` across rows, in first-seen order. */
function distinct(rows: Record<string, unknown>[], key: string): string[] {
    const seen = new Set<string>();
    const out: string[] = [];
    for (const r of rows) {
        const v = str(r[key]);
        if (!seen.has(v)) {
            seen.add(v);
            out.push(v);
        }
    }
    return out;
}

/** Group-by = the x + series (dimension/temporal) channels; metrics = the y channels' aggregations. */
export function buildXyQuery(values: ControlValues, ctx: QueryCtx): QuerySpec {
    const groupBy = [field(values.x), field(values.series)].filter((f): f is string => !!f);
    const metrics = (values.y ?? []).map((cv) => buildMetric(cv.agg ?? 'sum', cv.field));
    return { datasetId: ctx.datasetId, sourceName: ctx.sourceName, groupBy, metrics, filters: ctx.filters ?? null };
}

/** Pivot aggregated rows into {labels (x), one series per `series` value (or a single metric series)}. */
export function transformXy(rows: Record<string, unknown>[], values: ControlValues): VizProps {
    const xField = field(values.x);
    const seriesField = field(values.series);
    const ycv = values.y?.[0];
    if (!xField || !ycv) return { labels: [], series: [] };
    const mId = metricId(ycv.agg ?? 'sum', ycv.field);
    const labels = distinct(rows, xField);

    if (seriesField) {
        const seriesVals = distinct(rows, seriesField);
        const series = seriesVals.map((sv) => ({
            label: sv,
            data: labels.map((l) => {
                const r = rows.find((row) => str(row[xField]) === l && str(row[seriesField]) === sv);
                return num(r?.[mId]);
            }),
        }));
        return { labels, series };
    }

    const data = labels.map((l) => {
        const r = rows.find((row) => str(row[xField]) === l);
        return num(r?.[mId]);
    });
    return { labels, series: [{ label: ycv.agg ? `${ycv.agg}(${ycv.field})` : ycv.field, data }] };
}

/** Single headline metric over the (single-row, ungrouped) result — the KPI value. */
export function buildValueQuery(values: ControlValues, ctx: QueryCtx): QuerySpec {
    const cv = values.value?.[0] ?? values.y?.[0];
    const metrics = cv ? [buildMetric(cv.agg ?? 'sum', cv.field)] : [];
    return { datasetId: ctx.datasetId, sourceName: ctx.sourceName, groupBy: [], metrics, filters: ctx.filters ?? null };
}

export function transformValue(rows: Record<string, unknown>[], values: ControlValues): VizProps {
    const cv = values.value?.[0] ?? values.y?.[0];
    if (!cv) return { labels: [], series: [], value: 0 };
    const mId = metricId(cv.agg ?? 'sum', cv.field);
    return { labels: [], series: [], value: num(rows[0]?.[mId]) };
}
