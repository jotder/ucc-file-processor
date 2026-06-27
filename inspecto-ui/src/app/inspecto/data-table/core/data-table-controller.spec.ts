import { describe, expect, it } from 'vitest';
import { DataTableController } from './data-table-controller';

const ROWS = [
    { id: 1, name: 'alpha' },
    { id: 2, name: 'beta' },
];

describe('DataTableController', () => {
    it('infers columns and displays all rows', () => {
        const c = new DataTableController().setRows(ROWS);
        expect(c.columns()).toEqual(['id', 'name']);
        expect(c.displayedRows().length).toBe(2);
    });

    it('narrows displayed rows + CSV by the search text', () => {
        const c = new DataTableController().setRows(ROWS).setSearch('beta');
        expect(c.displayedRows().map((r) => r.id)).toEqual([2]);
        expect(c.csv()).toBe('id,name\n2,beta');
    });

    it('honors explicit columns for projection + CSV', () => {
        const c = new DataTableController({ columns: ['name'] }).setRows(ROWS);
        expect(c.columns()).toEqual(['name']);
        expect(c.csv()).toBe('name\nalpha\nbeta');
    });
});
