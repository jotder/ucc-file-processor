import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService, ComponentVersion } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentHistoryDialog } from './component-history.dialog';

const VERSIONS: ComponentVersion[] = [
    { type: 'dashboard', id: 'cdr', version: 2, savedAt: '2026-07-09T10:00:00Z', contentHash: 'hash-2', content: { name: 'cdr', tiles: [] } },
    { type: 'dashboard', id: 'cdr', version: 1, savedAt: '2026-07-08T10:00:00Z', contentHash: 'hash-1', content: { name: 'cdr', tiles: [] } },
];

function create(opts: { versions?: ComponentVersion[]; confirm?: boolean } = {}) {
    const close = vi.fn();
    const restore = vi.fn(() => of({ type: 'dashboard', name: 'cdr', ref: 'dashboard/cdr', content: {} }));
    const versions = vi.fn(() => of(opts.versions ?? VERSIONS));
    TestBed.configureTestingModule({
        imports: [ComponentHistoryDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { type: 'dashboard', id: 'cdr' } },
            { provide: MatDialogRef, useValue: { close } },
            { provide: ComponentsService, useValue: { versions, restore } },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(opts.confirm ?? true) } },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {}, warning: () => {} } },
        ],
    });
    const f = TestBed.createComponent(ComponentHistoryDialog);
    f.detectChanges();
    return { f, c: f.componentInstance, close, restore };
}

describe('ComponentHistoryDialog', () => {
    it('lists prior versions newest first', () => {
        const { c } = create();
        expect(c.loading()).toBe(false);
        expect(c.versions().map((v) => v.version)).toEqual([2, 1]);
    });

    it('confirms, restores the chosen version, and closes with true', async () => {
        const { c, close, restore } = create();
        await c.restore(VERSIONS[1]);   // restore v1
        expect(restore).toHaveBeenCalledWith('dashboard', 'cdr', 1);
        expect(close).toHaveBeenCalledWith(true);
    });

    it('does nothing when the restore confirm is declined', async () => {
        const { c, close, restore } = create({ confirm: false });
        await c.restore(VERSIONS[0]);
        expect(restore).not.toHaveBeenCalled();
        expect(close).not.toHaveBeenCalled();
    });

    it('shows an empty state with no prior versions', () => {
        const { c, f } = create({ versions: [] });
        expect(c.versions().length).toBe(0);
        expect(f.nativeElement.querySelector('inspecto-empty-state')).toBeTruthy();
    });

    it('has no a11y violations', async () => {
        const { f } = create();
        await expectNoA11yViolations(f.nativeElement);
    });
});
