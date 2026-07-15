import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, GridApi, RowSelectionOptions } from 'ag-grid-community';

import { toCsv, downloadCsv } from 'app/inspecto/data-table/core/csv';
import {
    actionsColumn,
    GridStateService,
    InspectoGridThemeService,
    INSPECTO_DEFAULT_COL_DEF,
    InspectoRowAction,
    noRowsOverlay,
    refreshAllCells,
} from 'app/inspecto/grid';
import { TreeGroupCell } from './tree-group-cell.component';
import { allParentIds, flattenTree, FlatTreeRow, seedExpanded, TreeNode } from './tree-types';

/**
 * Aligned tree-table: a left tree/hierarchy column + aligned value columns, built on Community ag-Grid by
 * flattening the forest into visible rows (design {@code docs/superpower/tree-table-design.md}). One
 * component covers breakdown / comparison / reconciliation / file-browser by configuration; cell plugins
 * are ordinary `columns` ColDefs (`statusBadgeHtml`, `varianceCell`, …) plus `[rowActions]` (action icons)
 * and `[multiSelect]` (checkbox selection). Reuses the shared grid theme + helpers verbatim.
 */
@Component({
    selector: 'inspecto-tree-table',
    standalone: true,
    imports: [AgGridAngular, MatButtonModule, MatIconModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './tree-table.component.html',
})
export class TreeTableComponent {
    readonly gridTheme = inject(InspectoGridThemeService);
    private gridState = inject(GridStateService);

    readonly nodes = input<TreeNode[]>([]);
    /** Right-hand value columns (ordinary ColDefs; use cellRenderer for badges / variance / icons). */
    readonly columns = input<ColDef[]>([]);
    readonly treeHeader = input('Name');
    readonly treeMinWidth = input(260);
    /** Depth auto-expanded on load (-1 = expand all). */
    readonly groupDefaultExpanded = input(1);
    readonly rowActions = input<InspectoRowAction[]>([]);
    readonly pinActions = input(false);
    readonly multiSelect = input(false);
    readonly singleSelect = input(false);
    readonly loading = input(false);
    readonly height = input('42rem');
    readonly autoHeight = input(false);
    readonly exportName = input('tree-export');
    readonly noRowsTitle = input('No data to display');
    readonly noRowsHint = input<string | undefined>(undefined);
    /** Opt-in layout persistence: a unique per-pane key. The expanded set and value-column widths
     *  then survive navigation and reload (per-space `localStorage` via {@link GridStateService}). */
    readonly stateKey = input<string | undefined>(undefined);

    readonly nodeClick = output<FlatTreeRow>();
    readonly selectionChange = output<FlatTreeRow[]>();
    readonly expandedChange = output<string[]>();

    private readonly expandDepth = computed(() => {
        const d = this.groupDefaultExpanded();
        return d < 0 ? Number.MAX_SAFE_INTEGER : d;
    });
    /** Expanded node ids — restored from the persisted layout (when `stateKey` is set and a snapshot
     *  exists), else seeded from `groupDefaultExpanded` on first data; then user toggles persist
     *  across `nodes` refreshes (hosts rebuild the forest on cell edits — the Access matrix —
     *  or after row actions — reconciliation Resolve; reseeding on every refresh would throw the
     *  user's expand/collapse state away). Ids that vanish from the data are inert in the set. */
    private readonly expanded = linkedSignal<TreeNode[], Set<string>>({
        source: this.nodes,
        computation: (nodes, previous) => {
            if (previous) return new Set(previous.value);
            const saved = this.gridState.load(this.stateKey())?.expanded;
            return saved ? new Set(saved) : seedExpanded(nodes, this.expandDepth());
        },
    });

    readonly flatRows = computed<FlatTreeRow[]>(() => flattenTree(this.nodes(), this.expanded()));

    private readonly treeColumn = computed<ColDef>(() => ({
        colId: '__tree',
        headerName: this.treeHeader(),
        minWidth: this.treeMinWidth(),
        flex: 2,
        sortable: false,
        cellRenderer: TreeGroupCell,
        cellRendererParams: { toggle: (id: string) => this.toggle(id) },
        valueGetter: (p: { data?: FlatTreeRow }) => p.data?.__label, // for CSV / quick-filter
    }));

    readonly gridColumns = computed<ColDef[]>(() => {
        const cols: ColDef[] = [this.treeColumn(), ...this.columns()];
        const acts = this.rowActions();
        return acts.length ? [...cols, actionsColumn(acts, 160, this.pinActions() ? 'right' : undefined)] : cols;
    });

    // Sorting off: reordering rows would break the parent→child flatten order.
    readonly defaultColDef = computed<ColDef>(() => ({
        ...INSPECTO_DEFAULT_COL_DEF,
        sortable: false,
        minWidth: 110,
        flex: 1,
    }));

    readonly rowSelectionValue = computed<RowSelectionOptions | 'single' | undefined>(() =>
        this.multiSelect()
            ? { mode: 'multiRow', enableClickSelection: false }
            : this.singleSelect()
              ? 'single'
              : undefined,
    );

    readonly noRows = computed(() => noRowsOverlay(this.noRowsTitle(), this.noRowsHint()));

    /** Stable row id so expand/collapse animates and selection survives re-flatten. */
    readonly getRowId = (p: { data: FlatTreeRow }): string => p.data.__id;

    // ── expand state ───────────────────────────────────────────────────────────────
    toggle(id: string): void {
        this.expanded.update((set) => {
            const next = new Set(set);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
        this.expandedChanged();
    }
    expandAll(): void {
        this.expanded.set(allParentIds(this.nodes()));
        this.expandedChanged();
    }
    collapseAll(): void {
        this.expanded.set(new Set());
        this.expandedChanged();
    }
    private expandedChanged(): void {
        const ids = [...this.expanded()];
        this.expandedChange.emit(ids);
        this.gridState.patch(this.stateKey(), { expanded: ids });
    }

    // ── grid events ──────────────────────────────────────────────────────────────
    onRowClicked(e: { data?: FlatTreeRow; event?: Event }): void {
        if (e.data) this.nodeClick.emit(e.data);
    }
    onSelectionChanged(e: { api: GridApi<FlatTreeRow> }): void {
        this.selectionChange.emit(e.api.getSelectedRows());
    }
    refresh(e: { api: GridApi }): void {
        refreshAllCells(e);
    }

    // ── layout persistence (`stateKey`) ──────────────────────────────────────────
    onGridReady(e: { api: GridApi }): void {
        const cols = this.gridState.load(this.stateKey())?.columns;
        if (cols?.length) e.api.applyColumnState({ state: cols, applyOrder: true });
    }

    /** Persist value-column widths after a user resize finishes. */
    onColumnStateChanged(e: { api: GridApi; finished?: boolean }): void {
        if (e.finished === false) return;
        if (!this.stateKey()) return;
        this.gridState.patch(this.stateKey(), { columns: e.api.getColumnState() });
    }

    exportCsv(): void {
        const header = this.treeHeader();
        const fields = this.columns()
            .map((c) => String(c.field))
            .filter((f) => f && f !== 'undefined');
        const rows = this.flatRows().map((r) => {
            const o: Record<string, unknown> = { [header]: '  '.repeat(r.__depth) + r.__label };
            for (const f of fields) o[f] = r[f];
            return o;
        });
        downloadCsv(this.exportName(), toCsv(rows, [header, ...fields]));
    }
}
