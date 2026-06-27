/**
 * Query-panel model — the structured "Query Core" behind the reusable queryable-table UI
 * ({@link QueryPanelComponent}). Projection (which columns) + a nested AND/OR condition tree (the row
 * filter). Compiled to an illustrative SQL string ({@link compileSql}) and evaluated in-browser for the
 * offline preview ({@link evaluateRows}). No persistence / templates / params here — that is the broader
 * Rule Builder (docs/rule-builder-design.md), of which this is the save-less core.
 */

export type ColumnType = 'number' | 'string' | 'date' | 'boolean';

export interface ColumnMeta {
    name: string;
    type: ColumnType;
}

/** Comparison operators; which apply to a column depends on its type (see `OPERATORS`). */
export type Operator =
    | '='
    | '!='
    | '<'
    | '<='
    | '>'
    | '>='
    | 'contains'
    | 'startsWith'
    | 'endsWith'
    | 'in'
    | 'between'
    | 'isNull'
    | 'isNotNull';

/** One leaf row-filter condition. `value` is raw text, interpreted by the column's type. */
export interface Condition {
    kind: 'condition';
    field: string;
    operator: Operator;
    value?: string; // single value; for `in` a comma list; for `between` the low bound
    value2?: string; // `between` high bound
}

/** A nested group of conditions/groups joined by a single boolean operator. */
export interface ConditionGroup {
    kind: 'group';
    op: 'AND' | 'OR';
    items: (Condition | ConditionGroup)[];
}

export type QueryItem = Condition | ConditionGroup;

export interface QueryModel {
    /** `'*'` (or empty) ⇒ all columns; otherwise the selected column names. */
    projection: string[] | '*';
    where: ConditionGroup;
    /** When non-null the user has hand-edited the SQL — it is authoritative and the builder is read-only. */
    sqlOverride?: string | null;
}

/** The data a panel queries over — offline, the rows are supplied directly by the host. */
export interface QuerySource {
    /** Logical table name used in the generated `FROM` clause. */
    name: string;
    rows: Record<string, unknown>[];
    /** Column metadata; inferred from `rows` when omitted. */
    columns?: ColumnMeta[];
}

export function emptyGroup(op: 'AND' | 'OR' = 'AND'): ConditionGroup {
    return { kind: 'group', op, items: [] };
}

/**
 * Bridge a typed row array (e.g. `EventRow[]`) to the loose `Record<string, unknown>[]` a {@link QuerySource}
 * holds. Typed interfaces lack an index signature so they aren't structurally assignable; at runtime they
 * are plain objects, so this is a safe widening for the query panel (which only reads by key).
 */
export function asRows<T extends object>(rows: readonly T[]): Record<string, unknown>[] {
    return rows as unknown as Record<string, unknown>[];
}

export function newCondition(field = ''): Condition {
    return { kind: 'condition', field, operator: '=', value: '' };
}
