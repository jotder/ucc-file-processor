import { describe, expect, it } from 'vitest';
import { evaluateRows } from './query-eval';
import { ColumnMeta, ConditionGroup, QueryModel, QuerySource } from './query-types';

const COLS: ColumnMeta[] = [
    { name: 'id', type: 'number' },
    { name: 'msisdn', type: 'string' },
    { name: 'dur', type: 'number' },
    { name: 'cell', type: 'string' },
];
const ROWS = [
    { id: 1, msisdn: '8801', dur: 30, cell: 'A' },
    { id: 2, msisdn: '8802', dur: 120, cell: 'B' },
    { id: 3, msisdn: '9903', dur: 90, cell: 'A' },
];
const SOURCE: QuerySource = { name: 'cdr', rows: ROWS, columns: COLS };

function m(where: ConditionGroup, projection: string[] | '*' = '*'): QueryModel {
    return { projection, where, sqlOverride: null };
}
function group(op: 'AND' | 'OR', items: unknown[]): ConditionGroup {
    return { kind: 'group', op, items: items as ConditionGroup['items'] };
}
const ids = (rows: Record<string, unknown>[]) => rows.map((r) => r['id']);

describe('evaluateRows', () => {
    it('no filter ⇒ all rows', () => {
        expect(evaluateRows(m(group('AND', [])), SOURCE).length).toBe(3);
    });

    it('numeric >=', () => {
        expect(ids(evaluateRows(m(group('AND', [{ kind: 'condition', field: 'dur', operator: '>=', value: '90' }])), SOURCE))).toEqual([2, 3]);
    });

    it('evaluates a nested (A AND (B OR C)) exactly', () => {
        const where = group('AND', [
            { kind: 'condition', field: 'cell', operator: '=', value: 'A' },
            group('OR', [
                { kind: 'condition', field: 'dur', operator: '<', value: '40' },
                { kind: 'condition', field: 'msisdn', operator: 'startsWith', value: '99' },
            ]),
        ]);
        expect(ids(evaluateRows(m(where), SOURCE))).toEqual([1, 3]);
    });

    it('projection narrows the returned columns', () => {
        const r = evaluateRows(m(group('AND', []), ['id', 'cell']), SOURCE);
        expect(Object.keys(r[0])).toEqual(['id', 'cell']);
    });

    it('in + contains under OR', () => {
        const where = group('OR', [
            { kind: 'condition', field: 'cell', operator: 'in', value: 'B' },
            { kind: 'condition', field: 'msisdn', operator: 'contains', value: '9903' },
        ]);
        expect(ids(evaluateRows(m(where), SOURCE))).toEqual([2, 3]);
    });

    it('ignores still-incomplete conditions', () => {
        const where = group('AND', [{ kind: 'condition', field: 'cell', operator: '=', value: '' }]);
        expect(evaluateRows(m(where), SOURCE).length).toBe(3);
    });
});
