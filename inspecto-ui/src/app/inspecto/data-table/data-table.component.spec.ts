import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DataTableComponent, DataTableTier } from './data-table.component';

function create(tier: DataTableTier = 'standard') {
    TestBed.configureTestingModule({
        imports: [DataTableComponent],
        providers: [provideNoopAnimations(), { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } }],
    });
    const f = TestBed.createComponent(DataTableComponent);
    f.componentRef.setInput('tier', tier);
    f.componentRef.setInput('rows', [
        { id: 1, name: 'alpha' },
        { id: 2, name: 'beta' },
    ]);
    f.detectChanges();
    return f;
}

describe('DataTableComponent', () => {
    it('mini = grid only (no toolbar, no query toggle)', () => {
        const c = create('mini').componentInstance;
        expect(c.showSearch()).toBe(false);
        expect(c.showExport()).toBe(false);
        expect(c.canQuery()).toBe(false);
        expect(c.gridColumns().map((x) => x.field)).toEqual(['id', 'name']);
    });

    it('standard = search + export, grid body', () => {
        const c = create('standard').componentInstance;
        expect(c.showSearch()).toBe(true);
        expect(c.showExport()).toBe(true);
        expect(c.canQuery()).toBe(false);
    });

    it('pro = grid by default + a query toggle (search shown in grid view)', () => {
        const c = create('pro').componentInstance;
        expect(c.canQuery()).toBe(true);
        expect(c.queryOpen()).toBe(false);
        expect(c.showSearch()).toBe(true);
        c.toggleQuery();
        expect(c.queryOpen()).toBe(true);
        expect(c.showSearch()).toBe(false); // the query panel owns search in query view
    });

    it('proMax exposes save-as-rule only while the query editor is open', () => {
        const c = create('proMax').componentInstance;
        expect(c.canQuery()).toBe(true);
        expect(c.showSave()).toBe(false);
        c.toggleQuery();
        expect(c.showSave()).toBe(true);
    });

    it('appends an actions column when rowActions are supplied', () => {
        const f = create('mini');
        f.componentRef.setInput('rowActions', [{ icon: 'heroicons_outline:eye', hint: 'View', onClick: () => {} }]);
        f.detectChanges();
        expect(f.componentInstance.gridColumns().some((col) => col.colId === 'actions')).toBe(true);
    });

    it('capability overrides win over the tier preset', () => {
        const f = create('mini');
        f.componentRef.setInput('exportable', true);
        f.detectChanges();
        expect(f.componentInstance.showExport()).toBe(true);
    });

    it('has no a11y violations (standard)', async () => {
        const f = create('standard');
        await expectNoA11yViolations(f.nativeElement);
    });
});
