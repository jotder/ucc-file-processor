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
    it('blocks save until title AND the 3-layer category are set, then creates and closes', () => {
        const { c, ref, api } = create();
        c.save();
        expect(api.create).not.toHaveBeenCalled();

        // Title alone is not enough for an incident — the 3-layer categorization is required.
        c.form.patchValue({ title: 'Late feed', severity: 'WARNING' });
        c.save();
        expect(api.create).not.toHaveBeenCalled();

        c.form.patchValue({ l1: 'Data Quality', l2: 'Timeliness', l3: 'Late arrival', tags: 'urgent, feed' });
        c.save();
        expect(api.create).toHaveBeenCalledWith({
            type: 'INCIDENT',
            title: 'Late feed',
            severity: 'WARNING',
            attributes: { category: 'Data Quality / Timeliness / Late arrival', tags: 'urgent,feed' },
        });
        expect(ref.close).toHaveBeenCalledWith(CREATED);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
