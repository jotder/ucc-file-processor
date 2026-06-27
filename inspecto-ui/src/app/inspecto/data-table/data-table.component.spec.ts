import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DataTableComponent, DataTableTier } from './data-table.component';

async function create(tier: DataTableTier = 'standard') {
    TestBed.configureTestingModule({
        imports: [DataTableComponent],
        providers: [provideNoopAnimations(), { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } }],
    });
    await TestBed.compileComponents(); // the component has a @defer block (the SQL editor)
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
    it('mini = grid only (no toolbar capabilities)', async () => {
        const c = (await create('mini')).componentInstance;
        expect(c.showSearch()).toBe(false);
        expect(c.showExport()).toBe(false);
        expect(c.showColumns()).toBe(false);
        expect(c.canQuery()).toBe(false);
        expect(c.hasToolbar()).toBe(false);
        expect(c.gridColumns().map((x) => x.field)).toEqual(['id', 'name']);
    });

    it('standard = search + columns + export (no SQL editor)', async () => {
        const c = (await create('standard')).componentInstance;
        expect(c.showSearch()).toBe(true);
        expect(c.showColumns()).toBe(true);
        expect(c.showExport()).toBe(true);
        expect(c.canQuery()).toBe(false);
        expect(c.showSave()).toBe(false);
    });

    it('pro = SQL editor (canQuery) + a generated SELECT; grid shows source rows until a run', async () => {
        const c = (await create('pro')).componentInstance;
        expect(c.canQuery()).toBe(true);
        expect(c.generatedSql()).toContain('SELECT');
        expect(c.generatedSql()).toContain('FROM');
        expect(c.proResult()).toBeNull();
        expect(c.displayRows().length).toBe(2);
        expect(c.filterOpen()).toBe(false);
        c.toggleFilter();
        expect(c.filterOpen()).toBe(true);
    });

    it('proMax = pro + save-as-rule always available', async () => {
        const c = (await create('proMax')).componentInstance;
        expect(c.canQuery()).toBe(true);
        expect(c.showSave()).toBe(true);
    });

    it('the column chooser drives the SQL projection', async () => {
        const c = (await create('pro')).componentInstance;
        expect(c.generatedSql()).toContain('SELECT *');
        c.onChosen(['name']);
        expect(c.generatedSql()).toContain('SELECT "name"');
        expect(c.generatedSql()).not.toContain('"id"');
    });

    it('appends an actions column when rowActions are supplied', async () => {
        const f = await create('mini');
        f.componentRef.setInput('rowActions', [{ icon: 'heroicons_outline:eye', hint: 'View', onClick: () => {} }]);
        f.detectChanges();
        expect(f.componentInstance.gridColumns().some((col) => col.colId === 'actions')).toBe(true);
    });

    it('capability overrides win over the tier preset', async () => {
        const f = await create('mini');
        f.componentRef.setInput('exportable', true);
        f.detectChanges();
        expect(f.componentInstance.showExport()).toBe(true);
    });

    it('has no a11y violations (standard)', async () => {
        const f = await create('standard');
        await expectNoA11yViolations(f.nativeElement);
    });
});
