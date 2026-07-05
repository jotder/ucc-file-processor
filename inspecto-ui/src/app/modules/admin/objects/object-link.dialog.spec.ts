import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { ObjectsService, OperationalObject } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ObjectLinkDialog } from './object-link.dialog';

const CANDIDATES = [
    { id: 'OBJ-1', objectType: 'INCIDENT', title: 'Self', status: 'OPEN' },
    { id: 'OBJ-2', objectType: 'CASE', title: 'Root cause case', status: 'OPEN' },
] as OperationalObject[];

const LINK = { from: 'OBJ-1', fromType: 'INCIDENT', to: 'OBJ-2', relationship: 'RELATED_TO' };

function create() {
    const ref = { close: vi.fn() };
    const api = {
        list: vi.fn(() => of(CANDIDATES)),
        link: vi.fn(() => of(LINK)),
    };
    TestBed.configureTestingModule({
        imports: [ObjectLinkDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { fromId: 'OBJ-1', fromType: 'INCIDENT' } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ObjectsService, useValue: api },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(ObjectLinkDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api };
}

describe('ObjectLinkDialog', () => {
    it('excludes the source object from candidates and only links once a target is picked', () => {
        const { c, ref, api } = create();
        expect(c.candidates.map((o) => o.id)).toEqual(['OBJ-2']);

        c.save();
        expect(api.link).not.toHaveBeenCalled();

        c.form.patchValue({ to: 'OBJ-2' });
        c.save();
        expect(api.link).toHaveBeenCalledWith('OBJ-1', 'OBJ-2', 'RELATED_TO');
        expect(ref.close).toHaveBeenCalledWith(LINK);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
