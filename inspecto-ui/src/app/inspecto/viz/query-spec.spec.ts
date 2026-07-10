import { describe, expect, it } from 'vitest';
import { ColumnMeta, emptyGroup } from 'app/inspecto/query';
import { aggExpression, buildMeasure, compileSpec, measureId } from './query-spec';
import { QuerySpec } from './viz-types';

const COLS: ColumnMeta[] = [
    { name: 'tariff', type: 'string' },
    { name: 'duration_s', type: 'number' },
];

describe('aggExpression / measureId / buildMeasure', () => {
    it('compiles aggregations with quoted identifiers', () => {
        expect(aggExpression('sum', 'duration_s')).toBe('SUM("duration_s")');
        expect(aggExpression('avg', 'duration_s')).toBe('AVG("duration_s")');
        expect(aggExpression('count', 'duration_s')).toBe('COUNT(*)');
        expect(aggExpression('countDistinct', 'tariff')).toBe('COUNT(DISTINCT "tariff")');
    });

    it('produces identifier-safe measure ids', () => {
        expect(measureId('sum', 'duration_s')).toBe('sum_duration_s');
        expect(measureId('count', 'x')).toBe('count');
    });

    it('builds a measure with id/expression/label plus the structured {agg, field} origin (M2)', () => {
        const m = buildMeasure('sum', 'duration_s');
        expect(m).toEqual({
            id: 'sum_duration_s',
            expression: 'SUM("duration_s")',
            label: 'sum(duration_s)',
            agg: 'sum',
            field: 'duration_s',
        });
        // `count` takes no column — the stamp carries the agg only.
        expect(buildMeasure('count', 'x')).toEqual({
            id: 'count', expression: 'COUNT(*)', label: 'count(x)', agg: 'count',
        });
    });
});

describe('compileSpec', () => {
    it('aggregates with GROUP BY when measures + groupBy are present', () => {
        const spec: QuerySpec = {
            datasetId: 'cdr',
            sourceName: 'cdr',
            groupBy: ['tariff'],
            measures: [buildMeasure('sum', 'duration_s')],
        };
        const sql = compileSpec(spec, COLS);
        expect(sql).toContain('SELECT "tariff", SUM("duration_s") AS "sum_duration_s"');
        expect(sql).toContain('FROM "cdr"');
        expect(sql).toContain('GROUP BY "tariff"');
    });

    it('projects raw group-by columns when there are no measures (no GROUP BY)', () => {
        const spec: QuerySpec = { datasetId: 'cdr', sourceName: 'cdr', groupBy: ['tariff'], measures: [] };
        const sql = compileSpec(spec);
        expect(sql).toContain('SELECT "tariff"');
        expect(sql).not.toContain('GROUP BY');
    });

    it('emits WHERE from filters, ORDER BY and LIMIT', () => {
        const where = emptyGroup('AND');
        where.items.push({ kind: 'condition', field: 'tariff', operator: '=', value: 'premium' });
        const spec: QuerySpec = {
            datasetId: 'cdr',
            sourceName: 'cdr',
            groupBy: ['tariff'],
            measures: [buildMeasure('sum', 'duration_s')],
            filters: where,
            orderBy: [{ field: 'sum_duration_s', dir: 'desc' }],
            limit: 10,
        };
        const sql = compileSpec(spec, COLS);
        expect(sql).toContain(`WHERE "tariff" = 'premium'`);
        expect(sql).toContain('ORDER BY "sum_duration_s" DESC');
        expect(sql).toContain('LIMIT 10');
    });
});
