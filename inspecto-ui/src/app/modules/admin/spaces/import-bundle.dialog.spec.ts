import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { ImportPreview, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ImportBundleData, ImportBundleDialog } from './import-bundle.dialog';

const PREVIEW: ImportPreview = {
    kind: 'data_source',
    sourceSpace: 'alpha',
    dataSources: ['orders'],
    files: ['orders_pipeline.toon', 'orders_schema.toon'],
    hasSpaceToon: false,
    conflicts: ['orders'],
    findings: { 'orders_pipeline.toon': [{ severity: 'WARNING', fieldPath: 'dirs.poll', message: 'check path' }] },
    valid: true,
};

function create(data: ImportBundleData, preview?: ImportPreview) {
    const stub = {
        availableSpaces: signal([{ id: 'alpha' }]),
        importPreview: () => of(preview ?? PREVIEW),
        importBundle: () => of({ kind: 'data_source', imported: ['orders'], pipelines: ['orders'], overwritten: true }),
        createFromBundle: () => of({ id: 'x', displayName: '', description: '', createdAt: '' }),
    } as unknown as SpacesService;
    TestBed.configureTestingModule({
        imports: [ImportBundleDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: SpacesService, useValue: stub },
            { provide: ToastrService, useValue: { warning: () => {}, error: () => {}, success: () => {} } },
        ],
    });
    const fixture = TestBed.createComponent(ImportBundleDialog);
    fixture.detectChanges();
    return fixture;
}

describe('ImportBundleDialog', () => {
    it('blocks import until conflicts are acknowledged via overwrite', () => {
        const c = create({ spaceId: 'alpha' }).componentInstance;
        c.file = new File([], 'b.zip');
        c.preview = PREVIEW; // valid but with a conflict
        expect(c.canImport()).toBe(false);
        c.overwrite.setValue(true);
        expect(c.canImport()).toBe(true);
    });

    it('blocks import when the preview is invalid', () => {
        const c = create({ spaceId: 'alpha' }).componentInstance;
        c.file = new File([], 'b.zip');
        c.preview = { ...PREVIEW, conflicts: [], valid: false };
        expect(c.canImport()).toBe(false);
    });

    it('blocks a duplicate new-space id inline in create-from-bundle mode', () => {
        const c = create({}).componentInstance;
        c.newId.setValue('alpha');
        expect(c.newId.hasError('duplicate')).toBe(true);
        c.newId.setValue('fresh');
        expect(c.newId.valid).toBe(true);
    });

    it('import mode has no a11y violations', async () => {
        const fixture = create({ spaceId: 'alpha' });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('create-from-bundle mode has no a11y violations', async () => {
        const fixture = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
