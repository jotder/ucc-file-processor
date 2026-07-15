import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { ComponentDef, ComponentsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentFormDialog } from './component-form.dialog';

const SAVED: ComponentDef = {
    type: 'grammar',
    name: 'csv-basic',
    ref: 'grammar:csv-basic',
    content: { delimiter: ',', has_header: true },
};

function create() {
    const ref = { close: vi.fn() };
    const api = {
        create: vi.fn(() => of(SAVED)),
        update: vi.fn(() => of(SAVED)),
    };
    TestBed.configureTestingModule({
        imports: [ComponentFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { kind: 'grammar' } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ComponentsService, useValue: api },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(ComponentFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api };
}

describe('ComponentFormDialog', () => {
    it('blocks submit until the id is valid, then creates and closes with the saved component', () => {
        const { c, ref, api } = create();
        c.submit();
        expect(api.create).not.toHaveBeenCalled();
        expect(ref.close).not.toHaveBeenCalled();

        c.form.patchValue({ id: 'csv-basic', hasHeader: true });
        c.submit();
        expect(api.create).toHaveBeenCalledWith('grammar', expect.objectContaining({ id: 'csv-basic', has_header: true }));
        expect(ref.close).toHaveBeenCalledWith({ saved: SAVED });
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('blocks a transform submit when the config textarea is not valid JSON', () => {
        const ref = { close: vi.fn() };
        const api = { create: vi.fn(() => of(SAVED)), update: vi.fn(() => of(SAVED)) };
        TestBed.configureTestingModule({
            imports: [ComponentFormDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MAT_DIALOG_DATA, useValue: { kind: 'transform' } },
                { provide: MatDialogRef, useValue: ref },
                { provide: ComponentsService, useValue: api },
                { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            ],
        });
        const fixture = TestBed.createComponent(ComponentFormDialog);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.form.patchValue({ id: 'my-transform', config: '{ not json' });
        expect(c.form.controls['config'].hasError('invalidJson')).toBe(true);
        c.submit();
        expect(api.create).not.toHaveBeenCalled();

        c.form.patchValue({ config: '{ "where": "1=1" }' });
        expect(c.form.controls['config'].hasError('invalidJson')).toBe(false);
        c.submit();
        expect(api.create).toHaveBeenCalledWith('transform', expect.objectContaining({ where: '1=1' }));
    });

    it('adds/removes partition chips for a sink', () => {
        const ref = { close: vi.fn() };
        const api = { create: vi.fn(() => of(SAVED)), update: vi.fn(() => of(SAVED)) };
        TestBed.configureTestingModule({
            imports: [ComponentFormDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MAT_DIALOG_DATA, useValue: { kind: 'sink' } },
                { provide: MatDialogRef, useValue: ref },
                { provide: ComponentsService, useValue: api },
                { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            ],
        });
        const fixture = TestBed.createComponent(ComponentFormDialog);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.addPartition({ value: 'year', chipInput: { clear: vi.fn() } } as never);
        c.addPartition({ value: 'month', chipInput: { clear: vi.fn() } } as never);
        expect(c.form.controls['partitions'].value).toEqual(['year', 'month']);
        c.removePartition('year');
        expect(c.form.controls['partitions'].value).toEqual(['month']);

        c.form.patchValue({ id: 'my-sink' });
        c.submit();
        expect(api.create).toHaveBeenCalledWith('sink', expect.objectContaining({ partitions: ['month'] }));
    });
});
