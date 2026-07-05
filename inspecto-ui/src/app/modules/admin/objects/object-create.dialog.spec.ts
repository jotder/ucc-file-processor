import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { ObjectsService, OperationalObject } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ObjectCreateDialog } from './object-create.dialog';

const CREATED = { id: 'OBJ-1', objectType: 'INCIDENT', title: 'Late feed' } as OperationalObject;

function create() {
    const ref = { close: vi.fn() };
    const api = { create: vi.fn(() => of(CREATED)) };
    TestBed.configureTestingModule({
        imports: [ObjectCreateDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { type: 'INCIDENT', label: 'Incident' } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ObjectsService, useValue: api },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(ObjectCreateDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api };
}

describe('ObjectCreateDialog', () => {
    it('blocks save until a title is set, then creates and closes with the object', () => {
        const { c, ref, api } = create();
        c.save();
        expect(api.create).not.toHaveBeenCalled();

        c.form.patchValue({ title: 'Late feed', severity: 'WARNING' });
        c.save();
        expect(api.create).toHaveBeenCalledWith({ type: 'INCIDENT', title: 'Late feed', severity: 'WARNING' });
        expect(ref.close).toHaveBeenCalledWith(CREATED);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
