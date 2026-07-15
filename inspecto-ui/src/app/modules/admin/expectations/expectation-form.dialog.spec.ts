import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { ExpectationsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ExpectationFormData, ExpectationFormDialog } from './expectation-form.dialog';

function create(data: ExpectationFormData) {
    TestBed.configureTestingModule({
        imports: [ExpectationFormDialog],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(), // the autocomplete option loaders inject root HTTP services
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: ExpectationsService, useValue: {} },
            { provide: ToastrService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(ExpectationFormDialog);
    fixture.detectChanges();
    return fixture;
}

describe('ExpectationFormDialog', () => {
    it('create mode blocks a duplicate id inline (asked at the save step) and has no a11y violations', async () => {
        const fixture = create({ existingNames: ['cdr_msisdn_not_null'] });
        const c = fixture.componentInstance;
        const name = c.saveForm.get('name')!;
        name.setValue('cdr_msisdn_not_null');
        expect(name.hasError('duplicate')).toBe(true);
        name.setValue('fresh_check');
        expect(name.hasError('duplicate')).toBe(false);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('kind-specific parameters appear via dependsOn (range shows min/max, regex shows pattern)', () => {
        const fixture = create({});
        const c = fixture.componentInstance;
        c.schemaForm.form.get('kind')!.setValue('range');
        fixture.detectChanges();
        expect(c.schemaForm.form.get('min')!.enabled).toBe(true);
        expect(c.schemaForm.form.get('pattern')!.disabled).toBe(true);

        c.schemaForm.form.get('kind')!.setValue('regex');
        fixture.detectChanges();
        expect(c.schemaForm.form.get('pattern')!.enabled).toBe(true);
        expect(c.schemaForm.form.get('min')!.disabled).toBe(true);
    });

    it('create flow advances to the save step once the config is valid, then creates', () => {
        const created: unknown[] = [];
        TestBed.configureTestingModule({
            imports: [ExpectationFormDialog],
            providers: [
                provideNoopAnimations(),
                provideHttpClient(),
                { provide: MatDialogRef, useValue: { close: () => {} } },
                { provide: MAT_DIALOG_DATA, useValue: {} },
                { provide: ExpectationsService, useValue: { create: (b: unknown) => { created.push(b); return { subscribe: () => {} }; } } },
                { provide: ToastrService, useValue: {} },
            ],
        });
        const fixture = TestBed.createComponent(ExpectationFormDialog);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.schemaForm.form.patchValue({ target: 'cdr_ingest', column: 'msisdn', kind: 'non_null' });
        c.save();
        expect(c.step()).toBe('save');
        c.saveForm.patchValue({ name: 'cdr_msisdn_not_null' });
        c.save();
        expect(created[0]).toMatchObject({ name: 'cdr_msisdn_not_null', target: 'cdr_ingest', column: 'msisdn' });
    });

    it('edit mode stays on the config step and shows the (immutable) name in the title', () => {
        const fixture = create({
            expectation: {
                name: 'cdr_duration_range', targetType: 'pipeline', target: 'cdr_ingest', column: 'duration_s',
                kind: 'range', min: 0, max: 86_400, pattern: null, refDataset: null, refColumn: null,
                severity: 'MAJOR', enabled: true, lastResult: null, createdAt: 1, updatedAt: 1,
            },
        });
        const c = fixture.componentInstance;
        expect(c.isEdit).toBe(true);
        expect(c.step()).toBe('config');
        expect(fixture.nativeElement.textContent).toContain('cdr_duration_range');
    });
});
