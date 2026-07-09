import { describe, expect, it } from 'vitest';
import { ColumnMeta } from 'app/inspecto/query';
import { buildDataset, inferRoles } from './dataset-types';

const COLS: ColumnMeta[] = [
    { name: 'id', type: 'number' },
    { name: 'cell_id', type: 'number' },
    { name: 'duration_s', type: 'number' },
    { name: 'tariff', type: 'string' },
    { name: 'event_time', type: 'date' },
];

describe('inferRoles', () => {
    it('assigns temporal to dates, measure to non-id numerics, dimension otherwise', () => {
        const roles = Object.fromEntries(inferRoles(COLS).map((c) => [c.name, c.role]));
        expect(roles['event_time']).toBe('temporal');
        expect(roles['duration_s']).toBe('measure');
        expect(roles['tariff']).toBe('dimension');
    });

    it('treats id and *_id numeric columns as dimensions, not measures', () => {
        const roles = Object.fromEntries(inferRoles(COLS).map((c) => [c.name, c.role]));
        expect(roles['id']).toBe('dimension');
        expect(roles['cell_id']).toBe('dimension');
    });
});

describe('buildDataset', () => {
    it('uses the name as the id and defaults the body', () => {
        const d = buildDataset('cdr_view', 'virtual', 'cdr');
        expect(d.id).toBe('cdr_view');
        expect(d.kind).toBe('virtual');
        expect(d.sourceName).toBe('cdr');
        expect(d.columns).toEqual([]);
        expect(d.measures).toEqual([]);
        expect(d.calculated).toEqual([]);
        expect(d.query).toBeNull();
    });

    it('carries through a provided body', () => {
        const cols = inferRoles(COLS);
        const d = buildDataset('cdr_view', 'virtual', 'cdr', {
            columns: cols,
            physicalRef: 'catalog/cdr',
            calculated: [{ name: 'total_with_tax', expr: 'amt * 2' }],
        });
        expect(d.columns).toHaveLength(COLS.length);
        expect(d.physicalRef).toBe('catalog/cdr');
        expect(d.calculated).toEqual([{ name: 'total_with_tax', expr: 'amt * 2' }]);
    });
});
