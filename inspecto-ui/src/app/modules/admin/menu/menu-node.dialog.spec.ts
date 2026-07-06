import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { MenuNodeDialog, MenuNodeDialogData } from './menu-node.dialog';

function make(data: MenuNodeDialogData) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [MenuNodeDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const f = TestBed.createComponent(MenuNodeDialog);
    f.detectChanges();
    return { c: f.componentInstance, ref };
}

describe('MenuNodeDialog', () => {
    it('blocks an empty name (required) and does not close', () => {
        const { c, ref } = make({ heading: 'Add menu', takenTitles: [] });
        c.save();
        expect(c.form.controls.title.hasError('required')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('blocks a duplicate sibling name inline (case-insensitive)', () => {
        const { c, ref } = make({ heading: 'Add menu', takenTitles: ['Revenue'] });
        c.form.controls.title.setValue('revenue');
        c.save();
        expect(c.form.controls.title.hasError('duplicate')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('returns the trimmed title + chosen icon on save', () => {
        const { c, ref } = make({ heading: 'Add menu', takenTitles: [] });
        c.form.controls.title.setValue('  Revenue  ');
        c.form.controls.icon.setValue('heroicons_outline:banknotes');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ title: 'Revenue', icon: 'heroicons_outline:banknotes' });
    });
});
