import { describe, expect, it } from 'vitest';
import { compileSql } from './query-sql';
import { ColumnMeta, ConditionGroup, QueryModel, QuerySource, emptyGroup } from './query-types';

const COLS: ColumnMeta[] = [
    { name: 'msisdn', type: 'string' },
    { name: 'duration_s', type: 'number' },
    { name: 'cell_id', type: 'string' },
];
const SOURCE: QuerySource = { name: 'cdr', rows: [], columns: COLS };

function model(where: ConditionGroup, projection: string[] | '*' = '*'): QueryModel {
    return { projection, where, sqlOverride: null };
}
function group(op: 'AND' | 'OR', items: unknown[]): ConditionGroup {
    return { kind: 'group', op, items: items as ConditionGroup['items'] };
}

describe('compileSql', () => {
    it('projects all columns with no filter', () => {
        expect(compileSql(model(emptyGroup()), SOURCE)).toBe('SELECT *\nFROM "cdr"');
    });

    it('selects specific columns', () => {
        expect(compileSql(model(emptyGroup(), ['msisdn', 'duration_s']), SOURCE)).toBe(
            'SELECT "msisdn", "duration_s"\nFROM "cdr"',
        );
    });

    it('renders typed comparisons (number unquoted, string quoted)', () => {
        const where = group('AND', [
            { kind: 'condition', field: 'duration_s', operator: '>=', value: '60' },
            { kind: 'condition', field: 'cell_id', operator: '=', value: 'CELL-1' },
        ]);
        expect(compileSql(model(where), SOURCE)).toBe(
            'SELECT *\nFROM "cdr"\nWHERE "duration_s" >= 60 AND "cell_id" = \'CELL-1\'',
        );
    });

    it('nests groups with OR and wraps them in parens', () => {
        const where = group('AND', [
            { kind: 'condition', field: 'duration_s', operator: '>', value: '0' },
            group('OR', [
                { kind: 'condition', field: 'cell_id', operator: '=', value: 'A' },
                { kind: 'condition', field: 'cell_id', operator: '=', value: 'B' },
            ]),
        ]);
        expect(compileSql(model(where), SOURCE)).toBe(
            'SELECT *\nFROM "cdr"\nWHERE "duration_s" > 0 AND ("cell_id" = \'A\' OR "cell_id" = \'B\')',
        );
    });

    it('renders contains / in / between / null', () => {
        expect(compileSql(model(group('AND', [{ kind: 'condition', field: 'msisdn', operator: 'contains', value: '880' }])), SOURCE)).toContain("\"msisdn\" LIKE '%880%'");
        expect(compileSql(model(group('AND', [{ kind: 'condition', field: 'cell_id', operator: 'in', value: 'A, B' }])), SOURCE)).toContain("\"cell_id\" IN ('A', 'B')");
        expect(compileSql(model(group('AND', [{ kind: 'condition', field: 'duration_s', operator: 'between', value: '1', value2: '9' }])), SOURCE)).toContain('"duration_s" BETWEEN 1 AND 9');
        expect(compileSql(model(group('AND', [{ kind: 'condition', field: 'msisdn', operator: 'isNull' }])), SOURCE)).toContain('"msisdn" IS NULL');
    });

    it('skips incomplete conditions so the SQL stays valid', () => {
        const where = group('AND', [{ kind: 'condition', field: 'msisdn', operator: '=', value: '' }]);
        expect(compileSql(model(where), SOURCE)).toBe('SELECT *\nFROM "cdr"');
    });
});
