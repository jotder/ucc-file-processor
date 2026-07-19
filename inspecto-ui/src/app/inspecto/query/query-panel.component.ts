import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef } from 'ag-grid-community';
import { autoColumns, INSPECTO_DEFAULT_COL_DEF, InspectoGridThemeService, noRowsOverlay } from 'app/inspecto/grid';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { inferColumns } from './query-columns';
import { evaluateRows } from './query-eval';
import { QueryConditionGroupComponent } from './query-condition-group.component';
import { compileSql } from './query-sql';
import { ColumnMeta, ConditionGroup, QueryModel, QuerySource, emptyGroup } from './query-types';

export interface QueryChange {
    model: QueryModel;
    sql: string;
}

/**
 * Reusable **queryable table**: pick columns (projection), build a nested AND/OR row filter, see the
 * generated SQL, drop into an Advanced SQL panel (hand-edit ⇒ one-way override, builder paused), and
 * preview matching rows live. Fully offline — the host passes the rows ({@link QuerySource}) and the
 * structured query is evaluated in-browser ({@link evaluateRows}); custom SQL shows the sample rows with a
 * "runs on the server" note. No save/templates here (that is the broader Rule Builder).
 */
@Component({
    selector: 'inspecto-query-panel',
    standalone: true,
    imports: [
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        AgGridAngular,
        InspectoAlertComponent,
        QueryConditionGroupComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './query-panel.component.html',
})
export class QueryPanelComponent {
    readonly gridTheme = inject(InspectoGridThemeService);
    readonly noRows = noRowsOverlay('No rows match', 'Adjust the filter — the preview updates live.');
    readonly defaultColDef: ColDef = { ...INSPECTO_DEFAULT_COL_DEF, filter: true, floatingFilter: true, minWidth: 110, flex: 1 };
    readonly pageSizes = [25, 50, 100];

    private readonly _source = signal<QuerySource>({ name: 'data', rows: [] });
    private initialModelApplied = false;

    /** A previously-saved model to seed the builder from on its first `source` assignment (e.g. re-opening
     *  a saved query for edit) — bind before `source` in the template so it's set when the setter reads it.
     *  Ignored on every later `source` change (a dataset switch still resets to empty, as before). */
    @Input() initialModel: QueryModel | null = null;

    /** The data to query. Reset of the builder happens only when the rows/name/columns actually change. */
    @Input({ required: true }) set source(s: QuerySource) {
        const cur = this._source();
        if (s.rows === cur.rows && s.name === cur.name && s.columns === cur.columns) return;
        this._source.set(s);
        this.columnsCache.set(s.columns ?? inferColumns(s.rows));
        const seed = !this.initialModelApplied ? this.initialModel : null;
        this.initialModelApplied = true;
        this.selectedColumns.set(seed && seed.projection !== '*' ? seed.projection : this.columnsCache().map((c) => c.name));
        this.where.set(seed?.where ?? emptyGroup('AND'));
        this.sqlOverride.set(seed?.sqlOverride ?? null);
        this.quickFilter.set('');
    }
    @Output() queryChange = new EventEmitter<QueryChange>();

    readonly columnsCache = signal<ColumnMeta[]>([]);
    readonly allColumnNames = computed(() => this.columnsCache().map((c) => c.name));
    readonly sourceName = computed(() => this._source().name);
    /** The source with resolved column metadata — so SQL/eval get the inferred types, not bare strings. */
    private readonly effectiveSource = computed<QuerySource>(() => ({
        name: this._source().name,
        rows: this._source().rows,
        columns: this.columnsCache(),
    }));

    readonly selectedColumns = signal<string[]>([]);
    readonly where = signal<ConditionGroup>(emptyGroup('AND'));
    readonly sqlOverride = signal<string | null>(null);
    readonly quickFilter = signal('');

    readonly overrideActive = computed(() => this.sqlOverride() !== null);

    readonly projection = computed<string[] | '*'>(() => {
        const sel = this.selectedColumns();
        const all = this.allColumnNames();
        return sel.length === 0 || sel.length === all.length ? '*' : sel;
    });
    readonly model = computed<QueryModel>(() => ({
        projection: this.projection(),
        where: this.where(),
        sqlOverride: this.sqlOverride(),
    }));
    readonly sql = computed(() =>
        this.overrideActive() ? this.sqlOverride() ?? '' : compileSql(this.model(), this.effectiveSource()),
    );

    readonly previewRows = computed(() =>
        this.overrideActive() ? this._source().rows : evaluateRows(this.model(), this.effectiveSource()),
    );
    readonly gridColumns = computed<ColDef[]>(() => {
        if (this.overrideActive()) {
            const rows = this.previewRows();
            return rows.length ? autoColumns(rows) : this.allColumnNames().map((f) => ({ field: f }));
        }
        const p = this.projection();
        const names = p === '*' ? this.allColumnNames() : p;
        return names.map((f) => ({ field: f }));
    });

    setProjection(cols: string[]): void {
        this.selectedColumns.set(cols);
        this.emit();
    }
    onWhereChanged(): void {
        this.where.update((w) => ({ ...w })); // new root ref so derived signals recompute
        this.emit();
    }

    /** Switch to the Advanced SQL panel, seeded from the current generated SQL (one-way override). */
    editSql(): void {
        this.sqlOverride.set(compileSql(this.model(), this.effectiveSource()));
        this.emit();
    }
    onOverrideInput(text: string): void {
        this.sqlOverride.set(text);
        this.emit();
    }
    revertToBuilder(): void {
        this.sqlOverride.set(null);
        this.emit();
    }

    private emit(): void {
        this.queryChange.emit({ model: this.model(), sql: this.sql() });
    }
}
