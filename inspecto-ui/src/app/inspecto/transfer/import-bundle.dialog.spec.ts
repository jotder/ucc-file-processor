import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BundleItem, buildBundle } from './bundle';
import { BundleTransferService } from './bundle-transfer.service';
import { ImportBundleData, ImportBundleDialog } from './import-bundle.dialog';

const TARGET: BundleItem[] = [{ kind: 'dataset', id: 'cdr_sample', content: { name: 'cdr_sample' } }];

function fileEvent(text: string): Event {
    return { target: { files: [new File([text], 'bundle.json')], value: '' } } as unknown as Event;
}

function create(data: ImportBundleData = {}, opts: { canAuthor?: boolean } = {}) {
    const write = vi.fn(() => of({}));
    TestBed.configureTestingModule({
        imports: [ImportBundleDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: vi.fn() } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: BundleTransferService, useValue: { loadAll: () => of(TARGET), write } },
            { provide: ToastrService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(opts.canAuthor !== false) } },
        ],
    });
    const fixture = TestBed.createComponent(ImportBundleDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, write };
}

describe('ImportBundleDialog', () => {
    it('fit-checks an uploaded bundle: new vs existing, drift, and missing requires', async () => {
        const { fixture, c } = create();
        // cdr_sample exists on target (identical → skip); a new widget bound to a missing dataset
        const bundle = buildBundle(
            [
                { kind: 'dataset', id: 'cdr_sample', content: { name: 'cdr_sample' } },
                { kind: 'widget', id: 'w1', content: { vizType: 'bar', datasetId: 'nope', controls: {} } },
            ],
            'staging',
        );
        await c.onFile(fileEvent(JSON.stringify(bundle)));
        fixture.detectChanges();
        expect(c.rows().map((r) => [r.item.id, r.exists, r.action])).toEqual([
            ['cdr_sample', true, 'skip'],
            ['w1', false, 'import'],
        ]);
        expect(c.missingRequires().map((r) => r.ref.id)).toEqual(['nope']);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('scopes rows to allowedKinds when a library imports', async () => {
        const { c } = create({ allowedKinds: ['widget'] });
        const bundle = buildBundle(
            [
                { kind: 'dataset', id: 'd1', content: {} },
                { kind: 'widget', id: 'w1', content: { vizType: 'bar', datasetId: 'd1', controls: {} } },
            ],
            null,
        );
        await c.onFile(fileEvent(JSON.stringify(bundle)));
        expect(c.rows().map((r) => r.item.id)).toEqual(['w1']);
    });

    it('applies actionable rows through the transfer service and closes with the count', async () => {
        const { c, write } = create();
        await c.onFile(fileEvent(JSON.stringify(buildBundle([{ kind: 'dataset', id: 'new_ds', content: { name: 'new_ds' } }], null))));
        c.apply();
        expect(write).toHaveBeenCalledWith(expect.objectContaining({ id: 'new_ds' }), false);
        expect(c.importedCount()).toBe(1);
    });

    it('a read-only lens cannot apply', async () => {
        const { c, write } = create({}, { canAuthor: false });
        await c.onFile(fileEvent(JSON.stringify(buildBundle([{ kind: 'dataset', id: 'new_ds', content: {} }], null))));
        c.apply();
        expect(write).not.toHaveBeenCalled();
    });
});
