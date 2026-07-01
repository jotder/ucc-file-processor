import { ChangeDetectionStrategy, Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { Condition, ColumnMeta, ConditionGroup, QueryConditionGroupComponent, emptyGroup } from 'app/inspecto/query';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { DrillEvent } from '../widgets/widget-host.component';
import { Widget } from '../widgets/widget-types';
import { WidgetsService } from '../widgets/widgets.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { Dashboard, DashboardTile, buildDashboard } from './dashboard-types';
import { DashboardsService } from './dashboards.service';
import { DashboardTileComponent } from './dashboard-tile.component';
import '../widgets/widget.kind'; // register widget kind + viz plugins (tiles call getViz)
import './dashboard.kind'; // register the dashboard kind

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
        QueryConditionGroupComponent,
        DashboardTileComponent,
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
    private toastr = inject(ToastrService);

    /** Route param — the dashboard id to edit; absent on the `new` route. */
    @Input() id?: string;

    readonly widgets = signal<Widget[]>([]);
    readonly datasets = signal<Dataset[]>([]);
    readonly tiles = signal<DashboardTile[]>([]);
    readonly filter = signal<ConditionGroup>(emptyGroup('AND'));
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
        }
    }

    private seed(d: Dashboard): void {
        this.tiles.set(d.tiles);
        this.filter.set(d.filter ?? emptyGroup('AND'));
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
        const dashboard = buildDashboard(name, this.tiles(), this.filter());
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
