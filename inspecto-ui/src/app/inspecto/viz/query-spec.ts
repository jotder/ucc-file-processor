import { ColumnMeta, compileWhere, quoteIdent } from 'app/inspecto/query';
import { runSql, SqlRunResult } from 'app/inspecto/data-table/sql/sql-run';
import { Aggregation, ChannelValue, QueryMeasure, QuerySpec } from './viz-types';

/**
 * Compile/run a {@link QuerySpec}. This is the QuerySpec→SQL boundary — the single swap seam between offline
 * AlaSQL (now) and a backend DuckDB endpoint (later). Reuses the Query Core's `quoteIdent` + `compileWhere` so
 * filter semantics match the rule/query surfaces exactly.
 */

/** The SQL aggregate for a measure channel's aggregation over a column (`count` ignores the column). */
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

/** A stable, identifier-safe measure id like `sum_duration_s` / `count`. */
export function measureId(agg: Aggregation, field: string): string {
    const base = agg === 'count' ? 'count' : `${agg}_${field}`;
    return base.replace(/[^A-Za-z0-9_]/g, '_');
}

/** Build a {@link QueryMeasure} from an aggregation + column. Stamps the structured `{agg, field}` origin
 *  so the server path (M2) can send validated identifiers instead of SQL text. */
export function buildMeasure(agg: Aggregation, field: string, label?: string): QueryMeasure {
    return {
        id: measureId(agg, field),
        expression: aggExpression(agg, field),
        label: label ?? `${agg}(${field})`,
        agg,
        ...(agg === 'count' ? {} : { field }),
    };
}

/** The measure a channel value contributes: a named measure's expression verbatim, else `agg(field)`. */
export function channelMeasure(cv: ChannelValue): QueryMeasure {
    if (cv.expression) return { id: channelMeasureId(cv), expression: cv.expression, label: cv.field };
    return buildMeasure(cv.agg ?? 'sum', cv.field);
}

/** The result-column id {@link channelMeasure} selects as — what transformProps reads back. */
export function channelMeasureId(cv: ChannelValue): string {
    if (cv.expression) return cv.field.replace(/[^A-Za-z0-9_]/g, '_');
    return measureId(cv.agg ?? 'sum', cv.field);
}

/**
 * Compile the spec to a DuckDB-style SELECT. With measures it aggregates (GROUP BY the dimensions); without
 * measures it projects the group-by columns raw. `cols` types the WHERE literals (defaults to untyped).
 */
export function compileSpec(spec: QuerySpec, cols: ColumnMeta[] = []): string {
    const dims = spec.groupBy.map(quoteIdent);
    const measureSel = spec.measures.map((m) => `${m.expression} AS ${quoteIdent(m.id)}`);
    const selectList = [...dims, ...measureSel];
    const select = selectList.length ? selectList.join(', ') : '*';
    const from = quoteIdent(spec.sourceName);

    let sql = `SELECT ${select}\nFROM ${from}`;
    const where = spec.filters ? compileWhere(spec.filters, cols) : '';
    if (where) sql += `\nWHERE ${where}`;
    if (spec.measures.length && spec.groupBy.length) sql += `\nGROUP BY ${dims.join(', ')}`;
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
