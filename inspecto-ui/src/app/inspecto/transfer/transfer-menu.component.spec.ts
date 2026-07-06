import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BundleItem } from './bundle';
import { BundleTransferService } from './bundle-transfer.service';
import { TransferMenuComponent } from './transfer-menu.component';

const WIDGET: BundleItem = { kind: 'widget', id: 'cost_by_tariff', content: { vizType: 'bar', datasetId: 'cdr_sample', controls: {} } };

function create(opts: { afterClosed?: number; canAuthor?: boolean } = {}) {
    const download = vi.fn();
    const buildExport = vi.fn((selected: BundleItem[]) => ({ bundle: { items: selected } as never, missing: [] as string[] }));
    const loadAll = vi.fn(() => of([WIDGET, { kind: 'dataset', id: 'cdr_sample', content: {} }] as BundleItem[]));
    const open = vi.fn(() => ({ afterClosed: () => of(opts.afterClosed ?? 0) }));
    TestBed.configureTestingModule({
        imports: [TransferMenuComponent],
        providers: [
            provideNoopAnimations(),
            { provide: BundleTransferService, useValue: { loadAll, buildExport, download } },
            { provide: MatDialog, useValue: { open } },
            { provide: ToastrService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(opts.canAuthor !== false) } },
        ],
    });
    const fixture = TestBed.createComponent(TransferMenuComponent);
    fixture.componentRef.setInput('items', [{ kind: 'widget', id: 'cost_by_tariff' }]);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, download, buildExport, loadAll, open };
}

describe('TransferMenuComponent', () => {
    it('resolves references to store content and exports this artifact only (no dependency closure)', () => {
        const { c, buildExport, download, loadAll } = create();
        c.exportThisOnly();
        expect(loadAll).toHaveBeenCalled();
        expect(buildExport).toHaveBeenCalledWith([WIDGET], expect.arrayContaining([WIDGET]), false);
        expect(download).toHaveBeenCalled();
    });

    it('exports with dependencies, resolving against the whole instance', () => {
        const { c, buildExport, download } = create();
        c.exportWithDeps();
        expect(buildExport).toHaveBeenCalledWith([WIDGET], expect.arrayContaining([WIDGET]), true);
        expect(download).toHaveBeenCalled();
    });

    it('opens the shared import dialog and emits changed when something imported', () => {
        const { c, open } = create({ afterClosed: 3 });
        const changed = vi.fn();
        c.changed.subscribe(changed);
        c.openImport();
        expect(open).toHaveBeenCalled();
        expect(changed).toHaveBeenCalled();
    });

    it('does not emit changed when the import wrote nothing', () => {
        const { c } = create({ afterClosed: 0 });
        const changed = vi.fn();
        c.changed.subscribe(changed);
        c.openImport();
        expect(changed).not.toHaveBeenCalled();
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
