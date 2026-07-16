import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { describe, expect, it, vi } from 'vitest';
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
        // both panels are hidden by default; each toggles independently
        expect(c.sqlOpen()).toBe(false);
        expect(c.filterOpen()).toBe(false);
        c.toggleSql();
        expect(c.sqlOpen()).toBe(true);
        c.toggleFilter();
        expect(c.filterOpen()).toBe(true);
    });

    it('onRunSqlBackend clears the client-run overlay and emits the SQL for the host', async () => {
        const c = (await create('pro')).componentInstance;
        c.proResult.set([{ id: 9 }]); // a prior client-run (AlaSQL) overlay
        let emitted: string | undefined;
        c.runOnServer.subscribe((s: string) => (emitted = s));
        c.onRunSqlBackend('SELECT * FROM "data"');
        expect(c.proResult()).toBeNull(); // overlay cleared so the host's fresh rows show
        expect(emitted).toBe('SELECT * FROM "data"');
    });

    it('proMax = pro + save-as-rule always available', async () => {
        const c = (await create('proMax')).componentInstance;
        expect(c.canQuery()).toBe(true);
        expect(c.showSave()).toBe(true);
    });

    it('serverPage: the honest "Load more" strip renders only while hasMore, and emits loadMore (R6b)', async () => {
        const f = await create('standard');
        f.componentRef.setInput('serverPage', true);
        f.componentRef.setInput('hasMore', true);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        expect(el.textContent).toContain('there may be more on the server');

        let emitted = false;
        f.componentInstance.loadMore.subscribe(() => (emitted = true));
        Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Load more'))?.click();
        expect(emitted).toBe(true);

        f.componentRef.setInput('hasMore', false);
        f.detectChanges();
        expect(el.textContent).not.toContain('there may be more on the server');
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

    it('pins the actions column to the right only when pinActions is set', async () => {
        const f = await create('mini');
        f.componentRef.setInput('rowActions', [{ icon: 'heroicons_outline:eye', hint: 'View', onClick: () => {} }]);
        f.detectChanges();
        const actions = () => f.componentInstance.gridColumns().find((col) => col.colId === 'actions');
        expect(actions()?.pinned).toBeUndefined();
        f.componentRef.setInput('pinActions', true);
        f.detectChanges();
        expect(actions()?.pinned).toBe('right');
    });

    it('capability overrides win over the tier preset', async () => {
        const f = await create('mini');
        f.componentRef.setInput('exportable', true);
        f.detectChanges();
        expect(f.componentInstance.showExport()).toBe(true);
    });

    it('preserves explicit cellRenderer / valueFormatter / headerName across a SQL run', async () => {
        // Regression: a badge `cellRenderer` (e.g. statusBadgeHtml) must survive the pro-tier AlaSQL
        // re-materialization instead of being rebuilt as a bare column and rendering an empty cell.
        const badge = (p: ICellRendererParams) => `<span>${p.value}</span>`;
        const fmtWhen = (p: { value: unknown }) => `t:${p.value}`;
        const columns: ColDef[] = [
            { field: 'severity', headerName: 'Severity', cellRenderer: badge },
            { field: 'epochMillis', headerName: 'When', valueFormatter: fmtWhen },
        ];
        const f = await create('pro');
        f.componentRef.setInput('columns', columns);
        f.detectChanges();
        const c = f.componentInstance;

        // Simulate a successful Run whose result carries the two mapped fields + one result-only field.
        c.proResult.set([{ severity: 'critical', epochMillis: 1, extra: 'x' }]);
        f.detectChanges();

        const cols = c.gridColumns();
        const sev = cols.find((x) => x.field === 'severity')!;
        const when = cols.find((x) => x.field === 'epochMillis')!;
        const extra = cols.find((x) => x.field === 'extra')!;
        expect(sev.cellRenderer).toBe(badge); // badge renderer preserved
        expect(when.valueFormatter).toBe(fmtWhen);
        expect(when.headerName).toBe('When');
        expect(extra.cellRenderer).toBeUndefined(); // result-only field falls back to a bare column
    });

    it('refresh() force-refreshes every column so non-actions cell renderers materialize', async () => {
        // Regression: refresh must not scope to `['actions']` — a badge cellRenderer column would then
        // stay empty on ag-grid's initial render (the bug on /alerts, /events, …).
        vi.useFakeTimers();
        try {
            const c = (await create('pro')).componentInstance;
            const refreshCells = vi.fn();
            c.refresh({ api: { isDestroyed: () => false, refreshCells } as never });
            vi.runAllTimers();
            expect(refreshCells).toHaveBeenCalledTimes(1);
            const arg = refreshCells.mock.calls[0][0];
            expect(arg.force).toBe(true);
            expect(arg.columns).toBeUndefined(); // all columns, not just 'actions'
        } finally {
            vi.useRealTimers();
        }
    });

    it('has no a11y violations (standard)', async () => {
        const f = await create('standard');
        await expectNoA11yViolations(f.nativeElement);
    });

    it('persists toolbar state under stateKey and restores it in a fresh instance', async () => {
        const storage = 'inspecto.grid.default.spec-table';
        localStorage.removeItem(storage);
        try {
            const f = await create('standard');
            f.componentRef.setInput('stateKey', 'spec-table');
            f.detectChanges();
            const c = f.componentInstance;
            c.search.set('alp');
            c.onChosen(['name']);
            f.detectChanges(); // flush the persist effect
            expect(JSON.parse(localStorage.getItem(storage)!)).toMatchObject({
                search: 'alp',
                chosen: ['name'],
            });

            // A fresh instance with the same key restores search (box open) + chosen projection.
            const f2 = TestBed.createComponent(DataTableComponent);
            f2.componentRef.setInput('stateKey', 'spec-table');
            f2.detectChanges();
            expect(f2.componentInstance.search()).toBe('alp');
            expect(f2.componentInstance.searchOpen()).toBe(true);
            expect(f2.componentInstance.chosen()).toEqual(['name']);
        } finally {
            localStorage.removeItem(storage);
        }
    });

    it('resetLayout returns to defaults and drops the persisted column layout', async () => {
        const storage = 'inspecto.grid.default.spec-reset';
        localStorage.setItem(
            storage,
            JSON.stringify({ search: 'x', chosen: ['id'], columns: [{ colId: 'id', width: 300 }] }),
        );
        try {
            const f = await create('standard');
            f.componentRef.setInput('stateKey', 'spec-reset');
            f.detectChanges();
            const c = f.componentInstance;
            expect(c.search()).toBe('x'); // restored
            c.resetLayout();
            f.detectChanges();
            expect(c.search()).toBe('');
            expect(c.searchOpen()).toBe(false);
            expect(c.chosen()).toBeNull();
            const after = JSON.parse(localStorage.getItem(storage) ?? '{}');
            expect(after.columns).toBeUndefined(); // layout gone; only default toolbar state re-saved
        } finally {
            localStorage.removeItem(storage);
        }
    });

    it('without a stateKey nothing is written to localStorage', async () => {
        const before = Object.keys(localStorage).filter((k) => k.startsWith('inspecto.grid.'));
        const c = (await create('standard')).componentInstance;
        c.search.set('zzz');
        const after = Object.keys(localStorage).filter((k) => k.startsWith('inspecto.grid.'));
        expect(after).toEqual(before);
    });

    // ── document-level keyboard layer (review R3) ─────────────────────────────────
    /** Stateful GridApi double for the nav focus/selection calls the keyboard layer makes. */
    function navApi(rows: Record<string, unknown>[]) {
        let focused: number | null = null;
        const selected = new Set<number>();
        return {
            api: {
                getDisplayedRowCount: () => rows.length,
                getAllDisplayedColumns: () => [{ getColId: () => 'id' }],
                getFocusedCell: () => (focused == null ? null : { rowIndex: focused }),
                ensureIndexVisible: () => undefined,
                setFocusedCell: (i: number) => (focused = i),
                getDisplayedRowAtIndex: (i: number) => ({
                    data: rows[i],
                    isSelected: () => selected.has(i),
                    setSelected: (v: boolean) => void (v ? selected.add(i) : selected.delete(i)),
                }),
            } as never,
            focusedIndex: () => focused,
            selectedIndexes: () => [...selected],
        };
    }

    /** jsdom has no layout, so the shortcut visibility check needs a stubbed offsetParent. */
    function makeVisible(el: HTMLElement): void {
        Object.defineProperty(el, 'offsetParent', { get: () => document.body });
    }

    function pressKey(key: string, target: EventTarget = document): void {
        target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    }

    it('keyNav: j/k move the focused row (clamped), Enter opens it, x toggles its selection', async () => {
        const rows = [
            { id: 1, name: 'alpha' },
            { id: 2, name: 'beta' },
        ];
        const f = await create('standard');
        f.componentRef.setInput('keyNav', true);
        f.detectChanges();
        makeVisible(f.nativeElement as HTMLElement);
        const c = f.componentInstance;
        const grid = navApi(rows);
        c.onGridReady(grid);

        pressKey('j');
        expect(grid.focusedIndex()).toBe(0); // first press lands on the first row
        pressKey('j');
        expect(grid.focusedIndex()).toBe(1);
        pressKey('j');
        expect(grid.focusedIndex()).toBe(1); // clamped at the last row
        pressKey('k');
        expect(grid.focusedIndex()).toBe(0);

        let opened: Record<string, unknown> | undefined;
        c.rowClick.subscribe((r: Record<string, unknown>) => (opened = r));
        pressKey('Enter');
        expect(opened).toEqual(rows[0]);

        pressKey('x');
        expect(grid.selectedIndexes()).toEqual([0]);
        pressKey('x');
        expect(grid.selectedIndexes()).toEqual([]);
    });

    it('keyNav stays inert while typing, while a dialog is open, and when not opted in', async () => {
        const f = await create('standard');
        makeVisible(f.nativeElement as HTMLElement);
        const c = f.componentInstance;
        const grid = navApi([{ id: 1 }]);
        c.onGridReady(grid);

        pressKey('j'); // keyNav not opted in
        expect(grid.focusedIndex()).toBeNull();

        f.componentRef.setInput('keyNav', true);
        f.detectChanges();
        const input = document.createElement('input');
        document.body.appendChild(input);
        try {
            pressKey('j', input); // typing in a field — exempt
            expect(grid.focusedIndex()).toBeNull();
        } finally {
            input.remove();
        }

        const overlay = document.createElement('div');
        overlay.className = 'cdk-overlay-container';
        const button = document.createElement('button');
        overlay.appendChild(button);
        document.body.appendChild(overlay);
        try {
            pressKey('j', button); // focus inside an open dialog — exempt
            expect(grid.focusedIndex()).toBeNull();
        } finally {
            overlay.remove();
        }
    });

    it("'/' opens and targets the quick filter of the visible searchable table", async () => {
        const f = await create('standard');
        makeVisible(f.nativeElement as HTMLElement);
        const c = f.componentInstance;
        expect(c.searchOpen()).toBe(false);
        pressKey('/');
        expect(c.searchOpen()).toBe(true);
    });
});
