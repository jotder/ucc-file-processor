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
import { ColumnMeta, ConditionGroup, QueryConditionGroupComponent, emptyGroup } from 'app/inspecto/query';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { Chart } from '../charts/chart-types';
import { ChartsService } from '../charts/charts.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { Dashboard, DashboardTile, buildDashboard } from './dashboard-types';
import { DashboardsService } from './dashboards.service';
import { DashboardTileComponent } from './dashboard-tile.component';
import '../charts/chart.kind'; // register chart kind + viz plugins (tiles call getViz)
import './dashboard.kind'; // register the dashboard kind

/**
 * Dashboard editor — compose saved charts into a grid. Add chart tiles, drag to reorder (CDK), toggle each
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
    private chartsApi = inject(ChartsService);
    private datasetsApi = inject(DatasetsService);
    private router = inject(Router);
    private toastr = inject(ToastrService);

    /** Route param — the dashboard id to edit; absent on the `new` route. */
    @Input() id?: string;

    readonly charts = signal<Chart[]>([]);
    readonly datasets = signal<Dataset[]>([]);
    readonly tiles = signal<DashboardTile[]>([]);
    readonly filter = signal<ConditionGroup>(emptyGroup('AND'));
    readonly editing = signal(false);
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);

    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
    });

    private readonly chartsById = computed(() => new Map(this.charts().map((c) => [c.id, c])));
    private readonly datasetsById = computed(() => new Map(this.datasets().map((d) => [d.id, d])));

    /** Union of column metadata across the tiled charts' datasets — the cross-filter's field choices. */
    readonly filterColumns = computed<ColumnMeta[]>(() => {
        const seen = new Map<string, ColumnMeta>();
        for (const tile of this.tiles()) {
            for (const col of this.datasetOf(tile)?.columns ?? []) {
                if (!seen.has(col.name)) seen.set(col.name, { name: col.name, type: col.type });
            }
        }
        return [...seen.values()];
    });

    chartOf(tile: DashboardTile): Chart | undefined {
        return this.chartsById().get(tile.chartId);
    }
    datasetOf(tile: DashboardTile): Dataset | undefined {
        const chart = this.chartOf(tile);
        return chart ? this.datasetsById().get(chart.datasetId) : undefined;
    }

    ngOnInit(): void {
        this.chartsApi.list().subscribe({ next: (c) => this.charts.set(c), error: () => this.toastr.warning('Could not load charts.') });
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

    addChart(chartId: string): void {
        if (!chartId) return;
        this.tiles.update((t) => [...t, { chartId, span: 1 }]);
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

    save(): void {
        const ctrl = this.form.controls.name;
        const name = String(ctrl.value ?? '').trim() || (this.id ?? '');
        if (!name || (ctrl.enabled && ctrl.invalid)) {
            this.form.markAllAsTouched();
            return;
        }
        if (!this.tiles().length) {
            this.toastr.warning('Add at least one chart.');
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
