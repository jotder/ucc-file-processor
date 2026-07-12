import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ObjectsService, OperationalObject } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { MergeCasesDialog } from './merge-cases.dialog';

function caseOf(id: string, updatedAt: number): OperationalObject {
    return {
        id, objectType: 'CASE', title: `case ${id}`, description: '', status: 'OPEN',
        createdAt: 1, updatedAt, closedAt: 0,
    };
}

function create(cases: OperationalObject[]) {
    const ref = { close: vi.fn() };
    const api = {
        mergeCases: vi.fn(() => of({ survivor: cases[0], merged: ['c1'], membersMoved: 3 })),
    } as unknown as ObjectsService;
    TestBed.configureTestingModule({
        imports: [MergeCasesDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { cases } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ObjectsService, useValue: api },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(MergeCasesDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api };
}

describe('MergeCasesDialog', () => {
    it('defaults the survivor to the most recently updated case and merges the rest into it', () => {
        const { c, api, ref } = create([caseOf('c1', 100), caseOf('c2', 300), caseOf('c3', 200)]);
        expect(c.survivorId()).toBe('c2');
        c.merge();
        expect(api.mergeCases).toHaveBeenCalledWith('c2', ['c1', 'c3'], expect.any(String));
        expect(ref.close).toHaveBeenCalledWith(true);
    });

    it('an explicitly picked survivor wins', () => {
        const { c, api } = create([caseOf('c1', 100), caseOf('c2', 300)]);
        c.survivorId.set('c1');
        c.merge();
        expect(api.mergeCases).toHaveBeenCalledWith('c1', ['c2'], expect.any(String));
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create([caseOf('c1', 100), caseOf('c2', 300)]);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
