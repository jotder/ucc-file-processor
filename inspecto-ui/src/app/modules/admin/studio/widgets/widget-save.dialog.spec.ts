import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { WidgetSaveData, WidgetSaveDialog } from './widget-save.dialog';

function create(data: WidgetSaveData) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [WidgetSaveDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(WidgetSaveDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('WidgetSaveDialog', () => {
    it('blocks save on a duplicate id (case-insensitive) on create, then saves once unique', () => {
        const { c, ref } = create({ suggestedId: 'cdr_view_bar', lockId: false, existingNames: ['cdr_view_bar'] });
        c.save();
        expect(ref.close).not.toHaveBeenCalled();
        expect(c.form.controls.name.hasError('duplicate')).toBe(true);
        c.form.controls.name.setValue('cdr_view_bar_2');
        c.save();
        expect(ref.close).toHaveBeenCalledWith(expect.objectContaining({ name: 'cdr_view_bar_2' }));
    });

    it('does not guard the id on edit (locked field, immutable id)', () => {
        const { c, ref } = create({ suggestedId: 'cdr_view_bar', lockId: true, existingNames: ['cdr_view_bar'] });
        c.save();
        expect(ref.close).toHaveBeenCalledWith(expect.objectContaining({ name: 'cdr_view_bar' }));
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create({ suggestedId: 'cdr_view_bar', lockId: false });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
