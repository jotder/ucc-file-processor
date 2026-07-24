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

    it('accepts any real icon from the full set, not just the curated few', () => {
        const { c, ref } = make({ heading: 'Add menu', takenTitles: [] });
        c.form.controls.title.setValue('Servers');
        c.form.controls.icon.setValue('heroicons_outline:server-stack'); // not in MENU_ICON_CHOICES
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ title: 'Servers', icon: 'heroicons_outline:server-stack' });
    });

    it('rejects an unknown icon value inline and does not close', () => {
        const { c, ref } = make({ heading: 'Add menu', takenTitles: [] });
        c.form.controls.title.setValue('X');
        c.form.controls.icon.setValue('heroicons_outline:not-a-real-icon');
        c.save();
        expect(c.form.controls.icon.hasError('unknownIcon')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('allows a blank icon (optional) and saves without one', () => {
        const { c, ref } = make({ heading: 'Add menu', takenTitles: [] });
        c.form.controls.title.setValue('No icon');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ title: 'No icon', icon: undefined });
    });

    it('filters the picker options by the typed term (id or label)', () => {
        const { c } = make({ heading: 'Add menu', takenTitles: [] });
        c.form.controls.icon.setValue('phone');
        expect(c.filteredIcons().length).toBeGreaterThan(0);
        expect(c.filteredIcons().every((o) => o.value.includes('phone') || o.label.toLowerCase().includes('phone'))).toBe(true);
    });
});
