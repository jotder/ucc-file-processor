import { ColumnMeta, compileWhere, quoteIdent } from 'app/inspecto/query';
import { runSql, SqlRunResult } from 'app/inspecto/data-table/sql/sql-run';
import { Aggregation, QueryMetric, QuerySpec } from './viz-types';

/**
 * Compile/run a {@link QuerySpec}. This is the QuerySpec→SQL boundary — the single swap seam between offline
 * AlaSQL (now) and a backend DuckDB endpoint (later). Reuses the Query Core's `quoteIdent` + `compileWhere` so
 * filter semantics match the rule/query surfaces exactly.
 */

/** The SQL aggregate for a metric channel's aggregation over a column (`count` ignores the column). */
export function aggExpression(agg: Aggregation, field: string): string {
    const id = quoteIdent(field);
    switch (agg) {
        case 'count':
            return 'COUNT(*)';
        case 'countDistinct':
            return `COUNT(DISTINCT ${id})`;
        case 'avg':
            return `AVG(${id})`;
        case 'min':
            return `MIN(${id})`;
        case 'max':
            return `MAX(${id})`;
        case 'sum':
        default:
            return `SUM(${id})`;
    }
}

/** A stable, identifier-safe metric id like `sum_duration_s` / `count`. */
export function metricId(agg: Aggregation, field: string): string {
    const base = agg === 'count' ? 'count' : `${agg}_${field}`;
    return base.replace(/[^A-Za-z0-9_]/g, '_');
}

/** Build a {@link QueryMetric} from an aggregation + column. */
export function buildMetric(agg: Aggregation, field: string, label?: string): QueryMetric {
    return { id: metricId(agg, field), expression: aggExpression(agg, field), label: label ?? `${agg}(${field})` };
}

/**
 * Compile the spec to a DuckDB-style SELECT. With metrics it aggregates (GROUP BY the dimensions); without
 * metrics it projects the group-by columns raw. `cols` types the WHERE literals (defaults to untyped).
 */
export function compileSpec(spec: QuerySpec, cols: ColumnMeta[] = []): string {
    const dims = spec.groupBy.map(quoteIdent);
    const metricSel = spec.metrics.map((m) => `${m.expression} AS ${quoteIdent(m.id)}`);
    const selectList = [...dims, ...metricSel];
    const select = selectList.length ? selectList.join(', ') : '*';
    const from = quoteIdent(spec.sourceName);

    let sql = `SELECT ${select}\nFROM ${from}`;
    const where = spec.filters ? compileWhere(spec.filters, cols) : '';
    if (where) sql += `\nWHERE ${where}`;
    if (spec.metrics.length && spec.groupBy.length) sql += `\nGROUP BY ${dims.join(', ')}`;
    if (spec.orderBy?.length) {
        const ob = spec.orderBy.map((o) => `${quoteIdent(o.field)} ${o.dir.toUpperCase()}`).join(', ');
        sql += `\nORDER BY ${ob}`;
    }
    if (spec.limit != null) sql += `\nLIMIT ${spec.limit}`;
    return sql;
}

/** Compile + run the spec over the supplied rows via offline AlaSQL. */
export function runSpec(spec: QuerySpec, rows: Record<string, unknown>[], cols: ColumnMeta[] = []): Promise<SqlRunResult> {
    return runSql(compileSpec(spec, cols), spec.sourceName, rows);
}
