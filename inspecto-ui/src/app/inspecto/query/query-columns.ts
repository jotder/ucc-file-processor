import { ColumnMeta, ColumnType, Operator } from './query-types';

/** An operator offered for a column type, with the number of value inputs it needs. */
export interface OperatorDef {
    op: Operator;
    label: string;
    /** 0 = no value (IS NULL), 1 = one value, 2 = two (BETWEEN), 'list' = comma-separated (IN). */
    arity: 0 | 1 | 2 | 'list';
}

const NULLS: OperatorDef[] = [
    { op: 'isNull', label: 'is null', arity: 0 },
    { op: 'isNotNull', label: 'is not null', arity: 0 },
];

const COMPARE: OperatorDef[] = [
    { op: '=', label: '=', arity: 1 },
    { op: '!=', label: '≠', arity: 1 },
    { op: '<', label: '<', arity: 1 },
    { op: '<=', label: '≤', arity: 1 },
    { op: '>', label: '>', arity: 1 },
    { op: '>=', label: '≥', arity: 1 },
];

/** Operators offered per column type. */
export const OPERATORS: Record<ColumnType, OperatorDef[]> = {
    number: [...COMPARE, { op: 'between', label: 'between', arity: 2 }, { op: 'in', label: 'in', arity: 'list' }, ...NULLS],
    date: [...COMPARE, { op: 'between', label: 'between', arity: 2 }, ...NULLS],
    string: [
        { op: '=', label: '=', arity: 1 },
        { op: '!=', label: '≠', arity: 1 },
        { op: 'contains', label: 'contains', arity: 1 },
        { op: 'startsWith', label: 'starts with', arity: 1 },
        { op: 'endsWith', label: 'ends with', arity: 1 },
        { op: 'in', label: 'in', arity: 'list' },
        ...NULLS,
    ],
    boolean: [{ op: '=', label: '=', arity: 1 }, { op: '!=', label: '≠', arity: 1 }, ...NULLS],
};

export function operatorsFor(type: ColumnType): OperatorDef[] {
    return OPERATORS[type];
}

export function operatorDef(type: ColumnType, op: Operator): OperatorDef | undefined {
    return OPERATORS[type].find((o) => o.op === op);
}

export function columnType(columns: ColumnMeta[], field: string): ColumnType {
    return columns.find((c) => c.name === field)?.type ?? 'string';
}

/** Map a column type as the `/db` routes report it (already-normalized `number`/`string`, or raw
 *  DuckDB/Postgres spellings like `BIGINT`/`TIMESTAMP`) to a {@link ColumnType}. */
export function dbColumnType(dbType: string | undefined): ColumnType {
    const t = (dbType ?? '').toUpperCase();
    if (/NUMBER|INT|DOUBLE|FLOAT|DECIMAL|NUMERIC|REAL/.test(t)) return 'number';
    if (/DATE|TIME/.test(t)) return 'date';
    if (/BOOL/.test(t)) return 'boolean';
    return 'string';
}

/** Guess column metadata from the keys + sample values of loose-map rows. */
export function inferColumns(rows: Record<string, unknown>[]): ColumnMeta[] {
    if (!rows.length) return [];
    return Object.keys(rows[0]).map((name) => ({ name, type: inferType(name, rows) }));
}

function inferType(name: string, rows: Record<string, unknown>[]): ColumnType {
    for (const r of rows) {
        const v = r[name];
        if (v == null || v === '') continue;
        if (typeof v === 'number') return 'number';
        if (typeof v === 'boolean') return 'boolean';
        const s = String(v);
        if (/^-?\d+(\.\d+)?$/.test(s)) return 'number';
        if (/^(true|false)$/i.test(s)) return 'boolean';
        // date: needs a 4-digit year + a date/time separator, so plain ids don't read as dates
        if (/\d{4}/.test(s) && /[-/:T]/.test(s) && !isNaN(Date.parse(s))) return 'date';
        return 'string';
    }
    return 'string';
}
