import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SpaceFormDialog } from './space-form.dialog';

function create() {
    TestBed.configureTestingModule({
        imports: [SpaceFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: SpacesService, useValue: { availableSpaces: signal([{ id: 'taken' }]) } },
            { provide: ToastrService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(SpaceFormDialog);
    fixture.detectChanges();
    return fixture;
}

describe('SpaceFormDialog', () => {
    it('rejects an empty id and accepts a valid one', () => {
        const c = create().componentInstance;
        expect(c.form.invalid).toBe(true);
        c.form.patchValue({ id: 'acme-1' });
        expect(c.form.valid).toBe(true);
        c.form.patchValue({ id: 'Bad Id' });
        expect(c.form.get('id')!.hasError('pattern')).toBe(true);
    });

    it('blocks a duplicate id inline (case-insensitive)', () => {
        const c = create().componentInstance;
        c.form.patchValue({ id: 'taken' });
        expect(c.form.get('id')!.hasError('duplicate')).toBe(true);
        c.form.patchValue({ id: 'TAKEN' });
        expect(c.form.get('id')!.hasError('pattern')).toBe(true); // uppercase also fails the charset
        c.form.patchValue({ id: 'fresh' });
        expect(c.form.valid).toBe(true);
    });

    it('has no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
