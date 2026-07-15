import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DbBrowserService } from 'app/inspecto/api';
import { DataBrowserComponent } from './data-browser.component';

describe('DataBrowserComponent', () => {
    const svc = {
        catalog: () => of({
            groups: [{
                id: 'stores', label: 'Data Stores', kind: 'parquet',
                tables: [{ name: 'orders', format: 'PARQUET', dataset: 'orders_ds' }],
            }],
        }),
        table: () => of({
            columns: [{ name: 'id', type: 'INTEGER' }, { name: 'name', type: 'VARCHAR' }],
            rows: [{ id: 1, name: 'alice' }],
            statistics: { rowCount: 1, elapsedMs: 1, truncated: false },
        }),
        query: () => of({ columns: [], rows: [], statistics: { rowCount: 0, elapsedMs: 1, truncated: false } }),
    };

    beforeEach(() => {
        TestBed.configureTestingModule({
            imports: [DataBrowserComponent],
            providers: [provideNoopAnimations(), { provide: DbBrowserService, useValue: svc }],
        });
    });

    it('lists the catalog stores', () => {
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Data Stores');
        expect(text).toContain('orders');
        expect(text).toContain('Select a table');   // right pane before selection
    });

    it('renders with no accessibility violations', async () => {
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('Load more widens the limit and re-runs the last read (table browse, or server SQL if used) — R6a', () => {
        const table = vi.fn(() => of({
            columns: [{ name: 'id', type: 'INTEGER' }],
            rows: [{ id: 1 }],
            statistics: { rowCount: 1, elapsedMs: 1, truncated: true },
        }));
        const query = vi.fn(() => of({ columns: [], rows: [], statistics: { rowCount: 0, elapsedMs: 1, truncated: true } }));
        TestBed.overrideProvider(DbBrowserService, { useValue: { ...svc, table, query } });
        const fixture = TestBed.createComponent(DataBrowserComponent);
        const c = fixture.componentInstance;
        fixture.detectChanges();
        c.select({ id: 'stores', label: 'Data Stores', kind: 'parquet', tables: [] }, { name: 'orders', format: 'PARQUET' });
        expect(c.limit()).toBe(200);

        c.loadMore();
        expect(table).toHaveBeenLastCalledWith(expect.objectContaining({ limit: 400 }));

        c.onServerSql('SELECT * FROM orders');
        c.loadMore();
        expect(query).toHaveBeenLastCalledWith(expect.objectContaining({ sql: 'SELECT * FROM orders', limit: 600 }));
    });
});
