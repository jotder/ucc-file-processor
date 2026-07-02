import { ChangeDetectionStrategy, Component, ElementRef, Input, OnInit, computed, inject, signal } from '@angular/core';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { AbstractControl, FormBuilder, FormsModule, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { Condition, ColumnMeta, ConditionGroup, QueryConditionGroupComponent, emptyGroup, evaluateRows } from 'app/inspecto/query';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DrillEvent } from '../widgets/widget-host.component';
import { Widget } from '../widgets/widget-types';
import { WidgetsService } from '../widgets/widgets.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { Dashboard, DashboardTile, buildDashboard } from './dashboard-types';
import { DashboardsService } from './dashboards.service';
import { DashboardTileComponent } from './dashboard-tile.component';
import { DashboardFilterBarComponent } from './dashboard-filter-bar.component';
import { DashboardDrillDrawerComponent } from './dashboard-drill-drawer.component';
import { SAMPLE_SOURCES } from '../datasets/dataset-sources';
import '../widgets/widget.kind'; // register widget kind + viz plugins (tiles call getViz)
import './dashboard.kind'; // register the dashboard kind

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Dashboard editor — compose saved widgets into a grid. Add widget tiles, drag to reorder (CDK), toggle each
 * tile's width, and set a dashboard **cross-filter** (Query Core condition group over the union of the tiles'
 * dataset columns) that re-renders every tile live. Save persists a `dashboard` component. Mock-first.
 */
@Component({
    selector: 'app-dashboard-editor',
    standalone: true,
    imports: [
        DragDropModule,
        ReactiveFormsModule,
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        RouterLink,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        QueryConditionGroupComponent,
        DashboardTileComponent,
        DashboardFilterBarComponent,
        DashboardDrillDrawerComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './dashboard-editor.component.html',
})
export class DashboardEditorComponent implements OnInit {
    private fb = inject(FormBuilder);
    private dashboardsApi = inject(DashboardsService);
    private widgetsApi = inject(WidgetsService);
    private datasetsApi = inject(DatasetsService);
    private router = inject(Router);
    private elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
    private toastr = inject(ToastrService);

    /** Route param — the dashboard id to edit; absent on the `new` route. */
    @Input() id?: string;

    readonly widgets = signal<Widget[]>([]);
    readonly datasets = signal<Dataset[]>([]);
    readonly tiles = signal<DashboardTile[]>([]);
    readonly filter = signal<ConditionGroup>(emptyGroup('AND'));
    readonly exposedFields = signal<string[]>([]);
    /** Index of the tile whose underlying rows are open in the drill-through drawer (null = closed). */
    readonly drillTileIndex = signal<number | null>(null);
    readonly editing = signal(false);
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);

    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
    });

    private readonly widgetsById = computed(() => new Map(this.widgets().map((w) => [w.id, w])));
    private readonly datasetsById = computed(() => new Map(this.datasets().map((d) => [d.id, d])));

    /** Union of column metadata across the tiled widgets' datasets — the cross-filter's field choices. */
    readonly filterColumns = computed<ColumnMeta[]>(() => {
        const seen = new Map<string, ColumnMeta>();
        for (const tile of this.tiles()) {
            for (const col of this.datasetOf(tile)?.columns ?? []) {
                if (!seen.has(col.name)) seen.set(col.name, { name: col.name, type: col.type });
            }
        }
        return [...seen.values()];
    });

    /** Distinct sample values per exposed field (from the tiles' datasets' sample rows) — the quick-filter
     *  pickers' choices. Capped so a high-cardinality column doesn't flood the select. */
    readonly exposedValues = computed<Record<string, string[]>>(() => {
        const exposed = this.exposedFields();
        if (!exposed.length) return {};
        const out: Record<string, Set<string>> = Object.fromEntries(exposed.map((f) => [f, new Set<string>()]));
        const seenSources = new Set<string>();
        for (const tile of this.tiles()) {
            const source = this.datasetOf(tile)?.sourceName;
            if (!source || seenSources.has(source)) continue;
            seenSources.add(source);
            for (const row of SAMPLE_SOURCES[source] ?? []) {
                for (const f of exposed) {
                    const v = row[f];
                    if (v != null && out[f].size < 20) out[f].add(String(v));
                }
            }
        }
        return Object.fromEntries(Object.entries(out).map(([f, set]) => [f, [...set].sort()]));
    });

    /** The drill-through drawer's contents — the open tile's sample rows with the live cross-filter applied
     *  (same offline evaluation the query panel previews with). */
    readonly drillView = computed<{ title: string; sourceName: string; rows: Record<string, unknown>[] } | null>(() => {
        const index = this.drillTileIndex();
        if (index == null) return null;
        const tile = this.tiles()[index];
        const dataset = tile ? this.datasetOf(tile) : undefined;
        if (!tile || !dataset) return null;
        const columns: ColumnMeta[] = dataset.columns.map((c) => ({ name: c.name, type: c.type }));
        const rows = evaluateRows(
            { projection: '*', where: this.filter() },
            { name: dataset.sourceName, rows: SAMPLE_SOURCES[dataset.sourceName] ?? [], columns },
        );
        return { title: this.widgetOf(tile)?.name ?? dataset.name, sourceName: dataset.sourceName, rows };
    });

    widgetOf(tile: DashboardTile): Widget | undefined {
        return this.widgetsById().get(tile.widgetId);
    }
    datasetOf(tile: DashboardTile): Dataset | undefined {
        const widget = this.widgetOf(tile);
        return widget ? this.datasetsById().get(widget.datasetId) : undefined;
    }

    ngOnInit(): void {
        this.widgetsApi.list().subscribe({ next: (w) => this.widgets.set(w), error: () => this.toastr.warning('Could not load widgets.') });
        this.datasetsApi.list().subscribe({ next: (d) => this.datasets.set(d), error: () => undefined });
        if (this.id) {
            this.editing.set(true);
            this.form.controls.name.setValue(this.id);
            this.form.controls.name.disable();
            this.dashboardsApi.get(this.id).subscribe({
                next: (d) => this.seed(d),
                error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load dashboard "${this.id}"`)),
            });
        } else {
            // Product-wide rule: block a duplicate id inline on create rather than relying on the server 409.
            this.dashboardsApi.list().subscribe((all) => {
                this.form.controls.name.addValidators(uniqueNameValidator(all.map((d) => d.id)));
                this.form.controls.name.updateValueAndValidity({ emitEvent: false });
            });
        }
    }

    private seed(d: Dashboard): void {
        this.tiles.set(d.tiles);
        this.filter.set(d.filter ?? emptyGroup('AND'));
        this.exposedFields.set(d.exposedFields ?? []);
    }

    addWidget(widgetId: string): void {
        if (!widgetId) return;
        this.tiles.update((t) => [...t, { widgetId, span: 1 }]);
    }
    removeTile(index: number): void {
        this.tiles.update((t) => t.filter((_, i) => i !== index));
    }
    toggleSpan(index: number): void {
        this.tiles.update((t) => t.map((tile, i) => (i === index ? { ...tile, span: tile.span === 1 ? 2 : 1 } : tile)));
    }
    drop(event: CdkDragDrop<DashboardTile[]>): void {
        this.tiles.update((t) => {
            const next = [...t];
            moveItemInArray(next, event.previousIndex, event.currentIndex);
            return next;
        });
    }

    /** New root ref so the tiles' `filter` input changes and they re-query. */
    onFilterChanged(): void {
        this.filter.update((f) => ({ ...f }));
    }

    /** A tile's drill-down click — toggle `field = value` in the cross-filter: add it if absent, remove it
     *  if the same value is clicked again. */
    onDrill({ field, value }: DrillEvent): void {
        const current = this.filter();
        const matches = (item: Condition | ConditionGroup): boolean =>
            item.kind === 'condition' && item.field === field && item.operator === '=' && item.value === value;
        const idx = current.items.findIndex(matches);
        const items =
            idx >= 0
                ? current.items.filter((_, i) => i !== idx)
                : [...current.items, { kind: 'condition', field, operator: '=', value } as Condition];
        this.filter.set({ ...current, items });
    }

    /** Download every rendered tile canvas as a PNG — the offline "export dashboard" (chart tiles only;
     *  table/KPI tiles have no canvas and export via their own surfaces). */
    exportPngs(): void {
        const canvases: NodeListOf<HTMLCanvasElement> = this.elementRef.nativeElement.querySelectorAll('app-dashboard-tile canvas');
        if (!canvases.length) {
            this.toastr.info('No chart tiles to export.');
            return;
        }
        const name = String(this.form.controls.name.value ?? 'dashboard');
        canvases.forEach((canvas, i) => {
            const link = document.createElement('a');
            link.href = canvas.toDataURL('image/png');
            link.download = `${name}-tile-${i + 1}.png`;
            link.click();
        });
    }

    save(): void {
        const ctrl = this.form.controls.name;
        const name = String(ctrl.value ?? '').trim() || (this.id ?? '');
        if (!name || (ctrl.enabled && ctrl.invalid)) {
            this.form.markAllAsTouched();
            return;
        }
        if (!this.tiles().length) {
            this.toastr.warning('Add at least one widget.');
            return;
        }
        const dashboard = buildDashboard(name, this.tiles(), this.filter(), this.exposedFields());
        this.saving.set(true);
        this.dashboardsApi.save(dashboard).subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success(`Dashboard "${name}" saved`);
                this.router.navigate(['/studio/dashboards']);
            },
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                this.toastr.error(e?.status === 503 ? 'Writes are disabled.' : apiErrorMessage(e, `Could not save "${name}"`));
            },
        });
    }
}
