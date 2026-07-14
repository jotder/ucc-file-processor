import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
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
});
