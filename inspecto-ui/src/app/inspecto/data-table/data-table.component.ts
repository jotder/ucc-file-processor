import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, GridApi, RowClickedEvent, RowSelectionOptions } from 'ag-grid-community';
import {
    actionsColumn,
    autoColumns,
    INSPECTO_DEFAULT_COL_DEF,
    InspectoGridThemeService,
    InspectoRowAction,
    noRowsOverlay,
    refreshAllCells,
} from 'app/inspecto/grid';
import {
    ColumnMeta,
    compileSql,
    compileSqlWithParams,
    ConditionGroup,
    emptyGroup,
    inferColumns,
    QueryConditionGroupComponent,
    QueryModel,
    QuerySource,
} from 'app/inspecto/query';
import { RuleSaveDialog, RuleTemplate } from 'app/inspecto/rule';
import { ColumnChooserComponent } from './column-chooser.component';
import { fieldNames } from './core/column-resolve';
import { downloadCsv, toCsv } from './core/csv';
import { quickFilterRows } from './core/quick-filter';
import { SqlEditorComponent } from './sql/sql-editor.component';
import { runSql } from './sql/sql-run';
import { SqlHistoryService } from './sql/sql-history.service';

/** The four product tiers (the "mobile version" analogy). */
export type DataTableTier = 'mini' | 'standard' | 'pro' | 'proMax';

interface Caps {
    search: boolean;
    export: boolean;
    /** The column chooser (show/hide in standard; SELECT projection in pro). */
    columns: boolean;
    query: boolean;
    save: boolean;
}

function capsFor(tier: DataTableTier): Caps {
    switch (tier) {
        case 'mini':
            return { search: false, export: false, columns: false, query: false, save: false };
        case 'standard':
            return { search: true, export: true, columns: true, query: false, save: false };
        case 'pro':
            return { search: true, export: true, columns: true, query: true, save: false };
        case 'proMax':
            return { search: true, export: true, columns: true, query: true, save: true };
    }
}

/**
 * **Data table** — one tiered component that consolidates every ag-Grid surface in the app:
 *
 * - **mini** — the themed grid (rows / columns / empty / loading / row actions / single-select).
 * - **standard** — mini + an icon-only toolbar: **column chooser**, **search**, **CSV export**.
 * - **pro** — standard + an always-on **SQL editor** (CodeMirror, lazy-loaded) that runs real SQL offline
 *   (AlaSQL) and re-renders the grid, plus an icon-toggled **filter builder** that regenerates the SQL.
 * - **pro max** — pro + **save as rule** (parameterized `:fieldValue` template via the rule store).
 *
 * Reusable logic lives in framework-free `core/` (csv · quick-filter · column-resolve) and `sql/`
 * (`runSql` · `SqlHistoryService` · the CodeMirror wiring), plus `inspecto/query` (compile/eval). This
 * component orchestrates; the heavy editor is `@defer`-loaded so mini/standard hosts never pull CodeMirror.
 */
@Component({
    selector: 'inspecto-data-table',
    standalone: true,
    imports: [
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        AgGridAngular,
        ColumnChooserComponent,
        QueryConditionGroupComponent,
        SqlEditorComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './data-table.component.html',
})
export class DataTableComponent {
    readonly gridTheme = inject(InspectoGridThemeService);
    private dialog = inject(MatDialog);
    private history = inject(SqlHistoryService);

    readonly tier = input<DataTableTier>('standard');
    readonly rows = input<unknown[]>([]);
    /** Explicit ag-Grid columns (status `cellRenderer`, date `valueFormatter`, …); omitted ⇒ one per row key. */
    readonly columns = input<ColDef[] | undefined>(undefined);
    readonly rowActions = input<InspectoRowAction[]>([]);
    /** Pin the row-actions column to the right so it stays visible when wide data columns overflow. */
    readonly pinActions = input(false);
    readonly loading = input(false);
    readonly pageSize = input(25);
    readonly height = input('42rem');
    readonly autoHeight = input(false);
    readonly singleSelect = input(false);
    /** Checkbox multi-select (mail-list style): header + row checkboxes; row *click* still only emits `rowClick`. */
    readonly multiSelect = input(false);
    readonly noRowsTitle = input('No data to display');
    readonly noRowsHint = input<string | undefined>(undefined);
    readonly exportName = input('export');
    /** Logical table name for the Pro query editor's `FROM` clause + rule id seed. */
    readonly sourceName = input('data');
    // Per-capability overrides (undefined ⇒ derive from tier).
    readonly searchable = input<boolean | undefined>(undefined);
    readonly exportable = input<boolean | undefined>(undefined);
    readonly queryable = input<boolean | undefined>(undefined);
    readonly savable = input<boolean | undefined>(undefined);

    readonly rowClick = output<Record<string, unknown>>();
    /** Multi-select: the currently checked rows, re-emitted on every selection change. */
    readonly selectionChange = output<Record<string, unknown>[]>();
    /** Pro Max: emitted after a rule template is saved via the built-in save dialog. */
    readonly ruleSaved = output<RuleTemplate>();

    /** ag-Grid selection config: checkbox multi-row wins over legacy single-select. */
    readonly rowSelectionValue = computed<RowSelectionOptions | 'single' | undefined>(() =>
        this.multiSelect()
            ? { mode: 'multiRow', enableClickSelection: false }
            : this.singleSelect()
              ? 'single'
              : undefined,
    );

    // ── toolbar state ────────────────────────────────────────────────────────────
    readonly search = signal('');
    readonly searchOpen = signal(false);
    /** Chosen columns (null ⇒ all). Drives grid visibility (standard) and SQL projection (pro). */
    readonly chosen = signal<string[] | null>(null);
    /** Pro: whether the filter-builder panel is expanded. */
    readonly filterOpen = signal(false);

    // ── pro query state ──────────────────────────────────────────────────────────
    readonly where = signal<ConditionGroup>(emptyGroup('AND'));
    /** Result of the last successful Run (null ⇒ show the source rows). */
    readonly proResult = signal<Record<string, unknown>[] | null>(null);
    readonly proError = signal<string | null>(null);
    readonly running = signal(false);

    private readonly caps = computed(() => capsFor(this.tier()));
    readonly canQuery = computed(() => this.queryable() ?? this.caps().query);
    readonly showSearch = computed(() => this.searchable() ?? this.caps().search);
    readonly showColumns = computed(() => this.caps().columns);
    readonly showExport = computed(() => this.exportable() ?? this.caps().export);
    readonly showSave = computed(() => this.savable() ?? this.caps().save);
    readonly hasToolbar = computed(
        () => this.showSearch() || this.showColumns() || this.showExport() || this.showSave() || this.canQuery(),
    );

    private readonly rowsRec = computed(() => this.rows() as Record<string, unknown>[]);
    readonly columnsCache = computed<ColumnMeta[]>(() => inferColumns(this.rowsRec()));

    /** Every available field name (explicit columns win, else the row keys). */
    readonly allFields = computed<string[]>(() => {
        const explicit = this.columns();
        if (explicit) return explicit.map((c) => String(c.field)).filter((f) => f && f !== 'undefined');
        return fieldNames(this.rowsRec());
    });

    /** Pro projection from the chooser: `'*'` when all (or none) are chosen, else the chosen names. */
    private readonly projection = computed<string[] | '*'>(() => {
        const sel = this.chosen();
        const all = this.allFields();
        return !sel || sel.length === 0 || sel.length === all.length ? '*' : sel;
    });

    readonly querySource = computed<QuerySource>(() => ({
        name: this.sourceName(),
        rows: this.rowsRec(),
        columns: this.columnsCache(),
    }));
    private readonly model = computed<QueryModel>(() => ({
        projection: this.projection(),
        where: this.where(),
        sqlOverride: null,
    }));
    /** SQL generated from the chooser + filter builder; the editor seeds/resets from this. */
    readonly generatedSql = computed(() => compileSql(this.model(), this.querySource()));
    /** The current editor text (resets to {@link generatedSql}, overridden by hand edits / history picks). */
    readonly currentSql = linkedSignal(() => this.generatedSql());

    readonly defaultColDef = computed<ColDef>(() => {
        const filterable = this.caps().columns;
        return {
            ...INSPECTO_DEFAULT_COL_DEF,
            // Always show a sort affordance: a neutral up/down glyph when the column is unsorted.
            sortable: true,
            unSortIcon: true,
            // Filter (a text search popup) lives behind a funnel icon in the header — no floating-filter row.
            filter: filterable,
            floatingFilter: false,
            suppressHeaderFilterButton: !filterable,
            minWidth: 110,
            flex: 1,
        };
    });

    /** Rows shown in the grid: the last Run result in pro, else the source rows. */
    readonly displayRows = computed<unknown[]>(() => this.proResult() ?? this.rows());

    readonly gridColumns = computed<ColDef[]>(() => {
        const acts = this.rowActions();
        const result = this.proResult();
        let base: ColDef[];
        if (result != null) {
            base = result.length ? this.resultColumns(result) : [];
        } else {
            const all = this.columns() ?? this.allFields().map((f) => ({ field: f }) as ColDef);
            const sel = this.chosen();
            base = sel ? all.filter((c) => sel.includes(String(c.field))) : all;
        }
        return acts.length ? [...base, actionsColumn(acts, 160, this.pinActions() ? 'right' : undefined)] : base;
    });

    /**
     * Columns for a SQL-run result: one per result key, reusing the host's explicit `ColDef`
     * (`cellRenderer` / `valueFormatter` / `headerName` / width …) whenever a result field matches an
     * explicit column, so badges and formatters survive the re-materialization. Result-only fields
     * (aggregates, aliases) fall back to a bare column keyed by name.
     */
    private resultColumns(rows: Record<string, unknown>[]): ColDef[] {
        const explicit = this.columns();
        if (!explicit) return autoColumns(rows);
        const byField = new Map(
            explicit.filter((c) => c.field != null).map((c) => [String(c.field), c] as const),
        );
        return Object.keys(rows[0]).map((k) => byField.get(k) ?? ({ field: k } as ColDef));
    }

    readonly noRows = computed(() => noRowsOverlay(this.noRowsTitle(), this.noRowsHint()));

    // ── toolbar handlers ───────────────────────────────────────────────────────────
    toggleSearch(): void {
        this.searchOpen.update((v) => !v);
    }
    onChosen(cols: string[]): void {
        this.chosen.set(cols);
    }
    toggleFilter(): void {
        this.filterOpen.update((v) => !v);
    }
    onWhereChanged(): void {
        this.where.update((w) => ({ ...w })); // new root ref so generatedSql recomputes
    }

    // ── pro: run / save ──────────────────────────────────────────────────────────
    onRunSql(sql: string): void {
        this.running.set(true);
        this.proError.set(null);
        runSql(sql, this.sourceName(), this.rowsRec()).then((res) => {
            this.running.set(false);
            if (res.ok) {
                this.proResult.set(res.rows);
                this.history.addRun(this.sourceName(), sql); // only successful runs enter history
            } else {
                this.proError.set(res.error ?? 'Query failed.'); // erroneous SQL: don't render, don't record
            }
        });
    }

    /** Open the save-as-rule dialog with the current query parameterized as `:fieldValue` binds. */
    onSaveRule(): void {
        const src = this.querySource();
        const m = this.model();
        const { sql: paramSql, params } = compileSqlWithParams(m, src);
        const displaySql = this.currentSql() || this.generatedSql();
        const diverged = displaySql.trim() !== this.generatedSql().trim();
        const modelToSave: QueryModel = diverged ? { ...m, sqlOverride: displaySql } : m;
        this.dialog
            .open(RuleSaveDialog, {
                width: '560px',
                autoFocus: false,
                data: { model: modelToSave, sql: displaySql, sourceName: this.sourceName(), params, paramSql },
            })
            .afterClosed()
            .subscribe((saved?: RuleTemplate) => {
                if (saved) this.ruleSaved.emit(saved);
            });
    }

    exportCsv(): void {
        const rowsR = this.displayRows() as Record<string, unknown>[];
        const visible = this.gridColumns()
            .map((c) => String(c.field))
            .filter((f) => f && f !== 'undefined' && f !== 'actions');
        const cols = fieldNames(rowsR, visible);
        const filtered = quickFilterRows(rowsR, this.search(), cols);
        downloadCsv(this.exportName(), toCsv(filtered, cols));
    }

    onRowClicked(e: { data?: Record<string, unknown> }): void {
        // A click on the selection checkbox toggles selection only — it is not a row "open".
        const target = (e as RowClickedEvent).event?.target as HTMLElement | null;
        if (target?.closest?.('.ag-selection-checkbox')) return;
        if (e.data) this.rowClick.emit(e.data);
    }

    onSelectionChanged(e: { api: GridApi }): void {
        this.selectionChange.emit(e.api.getSelectedRows());
    }

    refresh(e: { api: GridApi }): void {
        // Force-refresh every column (not just `actions`): ag-grid-angular 35 skips cell-renderer
        // materialization on the initial render, which otherwise leaves `statusBadgeHtml` badge
        // columns (severity / level / status …) empty until the next data change.
        refreshAllCells(e);
    }
}
