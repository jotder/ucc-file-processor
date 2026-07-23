import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';

import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RoleFormData, RoleFormDialog } from './role-form.dialog';

const VOCAB = ['canAuthorWorkbench', 'canOperateRuns', 'canConfigureAccess'];

function create(data: Partial<RoleFormData> = {}) {
    const ref = { close: vi.fn(), disableClose: false };
    TestBed.configureTestingModule({
        imports: [RoleFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(() => Promise.resolve(true)) } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: { vocabulary: VOCAB, existingNames: ['operations'], ...data } satisfies RoleFormData,
            },
        ],
    });
    const fixture = TestBed.createComponent(RoleFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('RoleFormDialog', () => {
    it('creates a role: lowercased name, vocabulary-ordered capabilities, parsed data scopes', () => {
        const { c, ref } = create();
        c.form.controls.name.setValue('Fraud-Analyst');
        c.toggle('canOperateRuns', true);
        c.toggle('canAuthorWorkbench', true);
        c.form.controls.dataScopes.setValue(' Fraud , billing ,, ');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({
            name: 'fraud-analyst',
            capabilities: ['canAuthorWorkbench', 'canOperateRuns'],   // vocabulary order, not click order
            dataScopes: ['fraud', 'billing'],
        });
    });

    it('rejects a duplicate or malformed name inline and does not close', () => {
        const { c, ref } = create();
        c.form.controls.name.setValue('operations');   // already exists
        c.save();
        expect(c.form.controls.name.hasError('duplicate')).toBe(true);
        c.form.controls.name.setValue('bad name!');
        c.save();
        expect(c.form.controls.name.hasError('pattern')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('edit mode keeps the name immutable and pre-selects the role\'s grants', () => {
        const { fixture, c, ref } = create({
            role: { name: 'operations', capabilities: ['canOperateRuns'], dataScopes: ['billing'] },
        });
        expect((fixture.nativeElement as HTMLElement).querySelector('input[formcontrolname="name"]')).toBeNull();
        expect(c.selected.has('canOperateRuns')).toBe(true);
        c.save();
        expect(ref.close).toHaveBeenCalledWith({
            name: 'operations',
            capabilities: ['canOperateRuns'],
            dataScopes: ['billing'],
        });
    });

    it('has no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
