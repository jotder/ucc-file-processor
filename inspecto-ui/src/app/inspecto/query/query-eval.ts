import { columnType } from './query-columns';
import { ColumnMeta, ColumnType, Condition, ConditionGroup, QueryModel, QuerySource } from './query-types';

/**
 * The offline query engine: run the structured model over the source rows in the browser. Used for the live
 * preview while the user builds the filter. (Custom hand-edited SQL is NOT evaluated here — that path shows
 * the sample rows with a "runs on the server" note.)
 */
export function evaluateRows(model: QueryModel, source: QuerySource): Record<string, unknown>[] {
    const cols = source.columns ?? [];
    const matched = source.rows.filter((row) => matchGroup(model.where, row, cols));
    if (model.projection === '*' || model.projection.length === 0) return matched;
    const keep = model.projection;
    return matched.map((row) => {
        const out: Record<string, unknown> = {};
        for (const k of keep) out[k] = row[k];
        return out;
    });
}

function matchGroup(group: ConditionGroup, row: Record<string, unknown>, cols: ColumnMeta[]): boolean {
    const results = group.items
        .filter((it) => it.kind === 'group' || isComplete(it))
        .map((it) => (it.kind === 'group' ? matchGroup(it, row, cols) : matchCondition(it, row, cols)));
    if (results.length === 0) return true; // empty / still-being-built group ⇒ no constraint
    return group.op === 'AND' ? results.every(Boolean) : results.some(Boolean);
}

/** A condition contributes to the predicate only once it has enough input to evaluate. */
export function isComplete(c: Condition): boolean {
    if (!c.field || !c.operator) return false;
    if (c.operator === 'isNull' || c.operator === 'isNotNull') return true;
    if (c.operator === 'between') return !!c.value && !!c.value2;
    return c.value != null && c.value !== '';
}

function matchCondition(c: Condition, row: Record<string, unknown>, cols: ColumnMeta[]): boolean {
    const raw = row[c.field];
    const t = columnType(cols, c.field);
    if (c.operator === 'isNull') return raw == null || raw === '';
    if (c.operator === 'isNotNull') return raw != null && raw !== '';
    if (raw == null) return false;
    const s = String(raw).toLowerCase();
    switch (c.operator) {
        case 'contains':
            return s.includes((c.value ?? '').toLowerCase());
        case 'startsWith':
            return s.startsWith((c.value ?? '').toLowerCase());
        case 'endsWith':
            return s.endsWith((c.value ?? '').toLowerCase());
        case 'in': {
            const items = (c.value ?? '').split(',').map((x) => x.trim()).filter(Boolean);
            return items.some((x) => cmp(raw, x, t) === 0);
        }
        case 'between':
            return cmp(raw, c.value ?? '', t) >= 0 && cmp(raw, c.value2 ?? '', t) <= 0;
        case '=':
            return cmp(raw, c.value ?? '', t) === 0;
        case '!=':
            return cmp(raw, c.value ?? '', t) !== 0;
        case '<':
            return cmp(raw, c.value ?? '', t) < 0;
        case '<=':
            return cmp(raw, c.value ?? '', t) <= 0;
        case '>':
            return cmp(raw, c.value ?? '', t) > 0;
        case '>=':
            return cmp(raw, c.value ?? '', t) >= 0;
        default:
            return false;
    }
}

/** Compare a row value with a typed string operand. Returns <0, 0, or >0. */
function cmp(raw: unknown, operand: string, type: ColumnType): number {
    if (type === 'number') {
        const a = Number(raw);
        const b = Number(operand);
        return a === b ? 0 : a < b ? -1 : 1;
    }
    if (type === 'date') {
        const a = Date.parse(String(raw));
        const b = Date.parse(operand);
        if (!isNaN(a) && !isNaN(b)) return a === b ? 0 : a < b ? -1 : 1;
    }
    if (type === 'boolean') {
        const a = raw === true || /^true$/i.test(String(raw));
        const b = /^true$/i.test(operand);
        return a === b ? 0 : a ? 1 : -1;
    }
    const a = String(raw);
    return a === operand ? 0 : a < operand ? -1 : 1;
}
