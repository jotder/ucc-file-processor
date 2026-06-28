import { ChangeDetectionStrategy, Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { ColumnMeta } from 'app/inspecto/query';
import {
    ControlValues,
    VizField,
    VizPlugin,
    VizProps,
    autoAssignChannels,
    getViz,
    recommend,
    runSpec,
} from 'app/inspecto/viz';
import { VizRenderComponent } from 'app/inspecto/viz/viz-render.component';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { SAMPLE_SOURCES } from '../datasets/dataset-sources';
import { Chart, buildChart } from './chart-types';
import { ChartSaveDialog } from './chart-save.dialog';
import { ChartsService } from './charts.service';
import { ExploreControlsComponent } from './explore-controls.component';
import './chart.kind'; // ensure the chart kind + viz plugins are registered

/**
 * Explore workbench — the chart builder. Pick a dataset, Show-Me recommends plugins, the field mapper
 * auto-assigns channels, and the chart re-renders live on every change (QuerySpec → offline AlaSQL →
 * transformProps → viz-render). Save persists a `chart` component. Mock-first; everything runs in-browser.
 */
@Component({
    selector: 'app-explore',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatSelectModule,
        RouterLink,
        InspectoAlertComponent,
        VizRenderComponent,
        ExploreControlsComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './explore.component.html',
})
export class ExploreComponent implements OnInit {
    private datasetsApi = inject(DatasetsService);
    private chartsApi = inject(ChartsService);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    private toastr = inject(ToastrService);

    /** Route param — the chart id to edit; absent on the `new` route. */
    @Input() id?: string;

    readonly datasets = signal<Dataset[]>([]);
    readonly selectedId = signal<string>('');
    readonly dataset = signal<Dataset | null>(null);
    readonly vizType = signal<string>('');
    readonly controls = signal<ControlValues>({});
    readonly props = signal<VizProps>({ labels: [], series: [] });
    readonly running = signal(false);
    readonly editing = signal(false);
    readonly writesDisabled = signal(false);

    readonly fields = computed<VizField[]>(() =>
        (this.dataset()?.columns ?? []).map((c) => ({ name: c.name, type: c.type, role: c.role, label: c.label })),
    );
    readonly recommended = computed<VizPlugin[]>(() => (this.dataset() ? recommend(this.fields()) : []));
    readonly plugin = computed<VizPlugin | null>(() => getViz(this.vizType()) ?? null);
    readonly sourceName = computed(() => this.dataset()?.sourceName ?? 'data');

    private readonly rows = computed(() => SAMPLE_SOURCES[this.dataset()?.sourceName ?? ''] ?? []);
    private readonly colMetas = computed<ColumnMeta[]>(() =>
        (this.dataset()?.columns ?? []).map((c) => ({ name: c.name, type: c.type })),
    );

    ngOnInit(): void {
        this.datasetsApi.list().subscribe({
            next: (d) => this.datasets.set(d),
            error: () => this.toastr.warning('Could not load datasets.'),
        });
        if (this.id) {
            this.editing.set(true);
            this.chartsApi.get(this.id).subscribe({
                next: (c) => this.seedFromChart(c),
                error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load chart "${this.id}"`)),
            });
        }
    }

    onSelectDataset(id: string): void {
        this.selectedId.set(id);
        this.datasetsApi.get(id).subscribe({
            next: (d) => {
                this.dataset.set(d);
                const top = this.recommended()[0]?.meta.type ?? 'table';
                this.setVizType(top);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load dataset "${id}"`)),
        });
    }

    /** Switch the visualization — re-auto-assign channels for the new plugin's controls, then render. */
    setVizType(type: string): void {
        this.vizType.set(type);
        const p = this.plugin();
        this.controls.set(p ? autoAssignChannels(p, this.fields()) : {});
        this.run();
    }

    onControls(values: ControlValues): void {
        this.controls.set(values);
        this.run();
    }

    private seedFromChart(c: Chart): void {
        this.selectedId.set(c.datasetId);
        this.vizType.set(c.vizType);
        this.controls.set(c.controls);
        this.datasetsApi.get(c.datasetId).subscribe({
            next: (d) => {
                this.dataset.set(d);
                this.run();
            },
            error: () => undefined,
        });
    }

    private run(): void {
        const plugin = this.plugin();
        const ds = this.dataset();
        if (!plugin || !ds) return;
        const spec = plugin.buildQuery(this.controls(), { datasetId: ds.id, sourceName: ds.sourceName, filters: null });
        this.running.set(true);
        runSpec(spec, this.rows(), this.colMetas())
            .then((res) => {
                this.props.set(plugin.transformProps(res.ok ? res.rows : [], this.controls()));
            })
            .catch(() => this.props.set({ labels: [], series: [] }))
            .finally(() => this.running.set(false));
    }

    save(): void {
        const ds = this.dataset();
        const plugin = this.plugin();
        if (!ds || !plugin) {
            this.toastr.warning('Pick a dataset and a visualization first.');
            return;
        }
        this.dialog
            .open(ChartSaveDialog, {
                data: { suggestedId: this.id ?? `${ds.id}_${this.vizType()}`, lockId: this.editing() },
                width: '420px',
            })
            .afterClosed()
            .subscribe((name?: string) => {
                if (!name) return;
                const chart = buildChart(name, ds.id, this.vizType(), this.controls());
                this.chartsApi.save(chart).subscribe({
                    next: () => {
                        this.toastr.success(`Chart "${name}" saved`);
                        this.router.navigate(['/studio/charts']);
                    },
                    error: (e) => {
                        if (e?.status === 503) this.writesDisabled.set(true);
                        this.toastr.error(
                            e?.status === 503 ? 'Writes are disabled.' : apiErrorMessage(e, `Could not save "${name}"`),
                        );
                    },
                });
            });
    }
}
