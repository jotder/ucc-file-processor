import { columnType } from './query-columns';
import { ColumnMeta, ColumnType, Condition, ConditionGroup, QueryModel, QuerySource } from './query-types';

/**
 * Compile the structured model to an illustrative DuckDB-style SQL string (builder → SQL only). Incomplete
 * conditions are skipped, so the SQL stays valid while the user is still building the filter.
 */
export function compileSql(model: QueryModel, source: QuerySource): string {
    const cols = source.columns ?? [];
    const proj =
        model.projection === '*' || model.projection.length === 0
            ? '*'
            : model.projection.map(quoteIdent).join(', ');
    const from = quoteIdent(source.name);
    const where = compileGroup(model.where, cols);
    return where ? `SELECT ${proj}\nFROM ${from}\nWHERE ${where}` : `SELECT ${proj}\nFROM ${from}`;
}

function compileGroup(group: ConditionGroup, cols: ColumnMeta[]): string {
    const parts = group.items
        .map((it) => (it.kind === 'group' ? wrap(compileGroup(it, cols)) : compileCondition(it, cols)))
        .filter((s) => s.length > 0);
    return parts.join(` ${group.op} `);
}

function wrap(s: string): string {
    return s ? `(${s})` : '';
}

function compileCondition(c: Condition, cols: ColumnMeta[]): string {
    if (!c.field || !c.operator) return '';
    const id = quoteIdent(c.field);
    const t = columnType(cols, c.field);
    switch (c.operator) {
        case 'isNull':
            return `${id} IS NULL`;
        case 'isNotNull':
            return `${id} IS NOT NULL`;
        case 'contains':
            if (!c.value) return '';
            return `${id} LIKE ${lit('%' + c.value + '%', 'string')}`;
        case 'startsWith':
            if (!c.value) return '';
            return `${id} LIKE ${lit(c.value + '%', 'string')}`;
        case 'endsWith':
            if (!c.value) return '';
            return `${id} LIKE ${lit('%' + c.value, 'string')}`;
        case 'in': {
            const items = (c.value ?? '').split(',').map((x) => x.trim()).filter(Boolean);
            return items.length ? `${id} IN (${items.map((x) => lit(x, t)).join(', ')})` : '';
        }
        case 'between':
            if (!c.value || !c.value2) return '';
            return `${id} BETWEEN ${lit(c.value, t)} AND ${lit(c.value2, t)}`;
        default:
            if (c.value == null || c.value === '') return '';
            return `${id} ${c.operator} ${lit(c.value, t)}`;
    }
}

function quoteIdent(name: string): string {
    return `"${name.replace(/"/g, '""')}"`;
}

function lit(v: string, type: ColumnType): string {
    if (type === 'number' && /^-?\d+(\.\d+)?$/.test(v)) return v;
    if (type === 'boolean' && /^(true|false)$/i.test(v)) return v.toUpperCase();
    return `'${v.replace(/'/g, "''")}'`;
}
