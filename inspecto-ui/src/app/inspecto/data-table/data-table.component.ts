import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, GridApi } from 'ag-grid-community';
import {
    actionsColumn,
    INSPECTO_DEFAULT_COL_DEF,
    InspectoGridThemeService,
    InspectoRowAction,
    noRowsOverlay,
    refreshActionsCells,
} from 'app/inspecto/grid';
import { compileSql, emptyGroup, QueryChange, QueryModel, QueryPanelComponent, QuerySource } from 'app/inspecto/query';
import { RuleSaveDialog, RuleTemplate } from 'app/inspecto/rule';
import { fieldNames } from './core/column-resolve';
import { downloadCsv, toCsv } from './core/csv';
import { quickFilterRows } from './core/quick-filter';

/** The four product tiers (the "mobile version" analogy). */
export type DataTableTier = 'mini' | 'standard' | 'pro' | 'proMax';

interface Caps {
    search: boolean;
    export: boolean;
    filter: boolean;
    query: boolean;
    save: boolean;
}

function capsFor(tier: DataTableTier): Caps {
    switch (tier) {
        case 'mini':
            return { search: false, export: false, filter: false, query: false, save: false };
        case 'standard':
            return { search: true, export: true, filter: true, query: false, save: false };
        case 'pro':
            return { search: true, export: true, filter: true, query: true, save: false };
        case 'proMax':
            return { search: true, export: true, filter: true, query: true, save: true };
    }
}

/**
 * **Data table** — one tiered component that consolidates every ag-Grid surface in the app:
 *
 * - **mini** — the themed grid (rows / columns / empty / loading / row actions / single-select).
 * - **standard** — mini + a toolbar: client-side **search**, per-column **filters**, sort, **CSV export**.
 * - **pro** — standard + the offline **SQL/query editor** (embeds {@link QueryPanelComponent}).
 * - **pro max** — pro + **save as rule template** (a `(saveRule)` ask the host wires to the rule store).
 *
 * Reusable logic lives in framework-free `core/` (csv · quick-filter · column-resolve · `DataTableController`)
 * and in `inspecto/query` (the Pro engine) — this component is a thin shell over them. Grid theming/cell
 * helpers come from `inspecto/grid`.
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
        QueryPanelComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './data-table.component.html',
})
export class DataTableComponent {
    readonly gridTheme = inject(InspectoGridThemeService);
    private dialog = inject(MatDialog);

    readonly tier = input<DataTableTier>('standard');
    readonly rows = input<unknown[]>([]);
    /** Explicit ag-Grid columns (status `cellRenderer`, date `valueFormatter`, …); omitted ⇒ one per row key. */
    readonly columns = input<ColDef[] | undefined>(undefined);
    readonly rowActions = input<InspectoRowAction[]>([]);
    readonly loading = input(false);
    readonly pageSize = input(25);
    readonly height = input('42rem');
    readonly autoHeight = input(false);
    readonly singleSelect = input(false);
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
    readonly queryChange = output<QueryChange>();
    /** Pro Max: emitted after a rule template is saved via the built-in save dialog. */
    readonly ruleSaved = output<RuleTemplate>();

    readonly search = signal('');
    private lastQuery: QueryChange | null = null;

    /** Pro/Pro Max: whether the query editor is showing (vs the interactive grid). */
    readonly queryOpen = signal(false);

    private readonly caps = computed(() => capsFor(this.tier()));
    /** A "Query" toggle is offered (pro+) — the grid stays the default view so row actions/click survive. */
    readonly canQuery = computed(() => this.queryable() ?? this.caps().query);
    readonly showSearch = computed(() => (this.searchable() ?? this.caps().search) && !this.queryOpen());
    readonly showExport = computed(() => this.exportable() ?? this.caps().export);
    readonly showSave = computed(() => (this.savable() ?? this.caps().save) && this.queryOpen());
    readonly hasToolbar = computed(() => this.showSearch() || this.showExport() || this.canQuery() || this.showSave());

    toggleQuery(): void {
        this.queryOpen.update((v) => !v);
    }

    private readonly rowsRec = computed(() => this.rows() as Record<string, unknown>[]);

    readonly defaultColDef = computed<ColDef>(() => ({
        ...INSPECTO_DEFAULT_COL_DEF,
        filter: this.caps().filter,
        floatingFilter: this.caps().filter,
        minWidth: 110,
        flex: 1,
    }));

    readonly gridColumns = computed<ColDef[]>(() => {
        const base = this.columns() ?? fieldNames(this.rowsRec()).map((f) => ({ field: f }) as ColDef);
        const acts = this.rowActions();
        return acts.length ? [...base, actionsColumn(acts)] : base;
    });

    readonly noRows = computed(() => noRowsOverlay(this.noRowsTitle(), this.noRowsHint()));
    readonly querySource = computed<QuerySource>(() => ({ name: this.sourceName(), rows: this.rowsRec() }));

    onQueryChange(c: QueryChange): void {
        this.lastQuery = c;
        this.queryChange.emit(c);
    }

    /** Open the save-as-rule dialog with the current query (or a default if none yet), emit on success. */
    onSaveRule(): void {
        const q = this.lastQuery ?? this.defaultQuery();
        this.dialog
            .open(RuleSaveDialog, {
                width: '520px',
                autoFocus: false,
                data: { model: q.model, sql: q.sql, sourceName: this.sourceName() },
            })
            .afterClosed()
            .subscribe((saved?: RuleTemplate) => {
                if (saved) this.ruleSaved.emit(saved);
            });
    }

    private defaultQuery(): QueryChange {
        const model: QueryModel = { projection: '*', where: emptyGroup(), sqlOverride: null };
        return { model, sql: compileSql(model, this.querySource()) };
    }

    exportCsv(): void {
        const explicit = (this.columns() ?? []).map((c) => String(c.field)).filter((f) => f && f !== 'undefined');
        const cols = fieldNames(this.rowsRec(), explicit);
        const rows = quickFilterRows(this.rowsRec(), this.search(), cols);
        downloadCsv(this.exportName(), toCsv(rows, cols));
    }

    onRowClicked(e: { data?: Record<string, unknown> }): void {
        if (e.data) this.rowClick.emit(e.data);
    }

    refresh(e: { api: GridApi }): void {
        refreshActionsCells(e);
    }
}
