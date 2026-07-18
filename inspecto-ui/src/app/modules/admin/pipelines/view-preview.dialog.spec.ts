import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { GAMMA_CONFIG } from '@gamma/services/config/config.constants';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { PipelineViewData, ViewsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ViewPreviewData, ViewPreviewDialog } from './view-preview.dialog';

const VIEW_DATA: PipelineViewData = {
    view: 'orders_view',
    columns: ['id', 'gross'],
    rowCount: 2,
    capped: false,
    rows: [{ id: 1, gross: 100 }, { id: 2, gross: 200 }],
};

function create(data: Partial<ViewPreviewData> = {}, api: Partial<ViewsService> = { data: () => of(VIEW_DATA) }) {
    TestBed.configureTestingModule({
        imports: [ViewPreviewDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: { viewName: 'orders_view', ...data } },
            { provide: ViewsService, useValue: api },
            { provide: GAMMA_CONFIG, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(ViewPreviewDialog);
    fixture.detectChanges();
    return fixture;
}

describe('ViewPreviewDialog', () => {
    it('loads and exposes the bounded view rows', () => {
        const c = create().componentInstance;
        expect(c.loading()).toBe(false);
        expect(c.result()?.rowCount).toBe(2);
        expect(c.columnsFor(VIEW_DATA).map((col) => col.field)).toEqual(['id', 'gross']);
    });

    it('surfaces a 409 (no derived_sql yet) as an error message', () => {
        const err = new HttpErrorResponse({
            status: 409,
            error: { error: { message: "view 'orders_view' has no derived_sql; re-run flow 'orders_etl' to concretise it" } },
        });
        const c = create({}, { data: () => throwError(() => err) }).componentInstance;
        expect(c.loading()).toBe(false);
        expect(c.error()).toContain('derived_sql');
    });

    it('has no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
