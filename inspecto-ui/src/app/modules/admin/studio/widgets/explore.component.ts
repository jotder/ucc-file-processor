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
    bucketRows,
    getViz,
    recommend,
    runSpec,
} from 'app/inspecto/viz';
import { VizRenderComponent } from 'app/inspecto/viz/viz-render.component';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { SAMPLE_SOURCES } from '../datasets/dataset-sources';
import { Widget, WidgetOptions, buildWidget } from './widget-types';
import { WidgetSaveDialog, WidgetSaveResult } from './widget-save.dialog';
import { WidgetOptionsDialog } from './widget-options.dialog';
import { WidgetsService } from './widgets.service';
import { ExploreControlsComponent } from './explore-controls.component';
import './widget.kind'; // ensure the widget kind + viz plugins are registered

/**
 * Explore workbench — the widget builder. Pick a dataset, Show-Me recommends plugins, the field mapper
 * auto-assigns channels, and the widget re-renders live on every change (QuerySpec → offline AlaSQL →
 * transformProps → viz-render). Save persists a `widget` component. Mock-first; everything runs in-browser.
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
    private widgetsApi = inject(WidgetsService);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    private toastr = inject(ToastrService);

    /** Route param — the widget id to edit; absent on the `new` route. */
    @Input() id?: string;

    readonly datasets = signal<Dataset[]>([]);
    readonly selectedId = signal<string>('');
    readonly dataset = signal<Dataset | null>(null);
    readonly vizType = signal<string>('');
    readonly controls = signal<ControlValues>({});
    readonly options = signal<WidgetOptions>({});
    readonly tags = signal<string[] | undefined>(undefined);
    readonly description = signal<string | undefined>(undefined);
    readonly props = signal<VizProps>({ labels: [], series: [] });
    readonly running = signal(false);
    readonly editing = signal(false);
    readonly writesDisabled = signal(false);

    readonly fields = computed<VizField[]>(() => {
        const rows = this.rows();
        return (this.dataset()?.columns ?? []).map((c) => ({
            name: c.name,
            type: c.type,
            role: c.role,
            label: c.label,
            cardinality: c.role === 'dimension' ? distinctCount(rows, c.name) : undefined,
        }));
    });
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
            this.widgetsApi.get(this.id).subscribe({
                next: (w) => this.seedFromWidget(w),
                error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load widget "${this.id}"`)),
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

    /** Open the advanced (cog) options dialog; applies the edited options on close, no-ops on cancel. */
    openOptions(): void {
        this.dialog
            .open(WidgetOptionsDialog, { data: this.options(), width: '480px' })
            .afterClosed()
            .subscribe((edited?: WidgetOptions) => {
                if (edited) this.options.set(edited);
            });
    }

    private seedFromWidget(w: Widget): void {
        this.selectedId.set(w.datasetId);
        this.vizType.set(w.vizType);
        this.controls.set(w.controls);
        this.options.set(w.options ?? {});
        this.tags.set(w.tags);
        this.description.set(w.description);
        this.datasetsApi.get(w.datasetId).subscribe({
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
        const x = this.controls().x?.[0];
        const rows = x ? bucketRows(this.rows(), x.field, x.grain) : this.rows();
        runSpec(spec, rows, this.colMetas())
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
            .open(WidgetSaveDialog, {
                data: {
                    suggestedId: this.id ?? `${ds.id}_${this.vizType()}`,
                    lockId: this.editing(),
                    tags: this.tags(),
                    description: this.description(),
                },
                width: '420px',
            })
            .afterClosed()
            .subscribe((result?: WidgetSaveResult) => {
                if (!result) return;
                const { name, tags, description } = result;
                const widget = buildWidget(name, ds.id, this.vizType(), this.controls(), {
                    options: this.options(),
                    tags,
                    description,
                });
                this.widgetsApi.save(widget).subscribe({
                    next: () => {
                        this.toastr.success(`Widget "${name}" saved`);
                        this.router.navigate(['/studio/widgets']);
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

/** Distinct-value count for `field` across `rows` — the cardinality hint Show-Me uses to penalise, e.g., a
 *  pie chart with too many slices. */
function distinctCount(rows: Record<string, unknown>[], field: string): number {
    return new Set(rows.map((r) => r[field])).size;
}
