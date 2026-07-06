import { ChangeDetectionStrategy, Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { ComponentType, ComponentsService, apiErrorMessage } from 'app/inspecto/api';
import { ColumnMeta } from 'app/inspecto/query';
import {
    ControlValues,
    VizField,
    VizPlugin,
    VizProps,
    allViz,
    autoAssignChannels,
    bucketRows,
    getViz,
    recommend,
    runSpec,
} from 'app/inspecto/viz';
import { VizRenderComponent } from 'app/inspecto/viz/viz-render.component';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { TransferMenuComponent } from 'app/inspecto/transfer';
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
        TransferMenuComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './explore.component.html',
})
export class ExploreComponent implements OnInit {
    private datasetsApi = inject(DatasetsService);
    private widgetsApi = inject(WidgetsService);
    private componentsApi = inject(ComponentsService);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    private toastr = inject(ToastrService);

    /** Route param — the widget id to edit; absent on the `new` route. */
    @Input() id?: string;

    /** This saved widget as a transfer reference — export is offered only in edit mode. */
    get transferItems(): { kind: 'widget'; id: string }[] {
        return this.id ? [{ kind: 'widget', id: this.id }] : [];
    }

    readonly datasets = signal<Dataset[]>([]);
    readonly existingWidgetIds = signal<string[]>([]);
    readonly selectedId = signal<string>('');
    readonly dataset = signal<Dataset | null>(null);
    /** A saved query this widget is bound to (R3 lineage; preserved across an edit round-trip). Rendering
     *  still runs the dataset+controls path — query-driven rendering is a follow-on. */
    readonly boundQueryId = signal<string | undefined>(undefined);
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
        const ds = this.dataset();
        const columns: VizField[] = (ds?.columns ?? []).map((c) => ({
            name: c.name,
            type: c.type,
            role: c.role,
            label: c.label,
            cardinality: c.role === 'dimension' ? distinctCount(rows, c.name) : undefined,
        }));
        // Named measures join the field list as ready-made aggregates (expression carried verbatim).
        const measures: VizField[] = (ds?.measures ?? []).map((m) => ({
            name: m.id,
            type: 'number',
            role: 'measure',
            label: m.label,
            expression: m.expression,
        }));
        return [...columns, ...measures];
    });
    readonly recommended = computed<VizPlugin[]>(() => (this.dataset() ? recommend(this.fields()) : []));
    readonly plugin = computed<VizPlugin | null>(() => getViz(this.vizType()) ?? null);
    readonly sourceName = computed(() => this.dataset()?.sourceName ?? 'data');

    /** The view-bound plugins (geo-map / link-analysis) — offered without a dataset; excluded from Show-Me. */
    readonly viewPlugins: VizPlugin[] = allViz().filter((p) => !!p.meta.viewKind);
    readonly viewBound = computed(() => !!this.plugin()?.meta.viewKind);
    /** The selected saved view (a view-bound widget's binding) + the picker's choices. */
    readonly viewId = signal<string>('');
    readonly savedViews = signal<{ id: string; name: string }[]>([]);

    private readonly rows = computed(() => SAMPLE_SOURCES[this.dataset()?.sourceName ?? ''] ?? []);
    private readonly colMetas = computed<ColumnMeta[]>(() =>
        (this.dataset()?.columns ?? []).map((c) => ({ name: c.name, type: c.type })),
    );

    ngOnInit(): void {
        this.datasetsApi.list().subscribe({
            next: (d) => this.datasets.set(d),
            error: () => this.toastr.warning('Could not load datasets.'),
        });
        this.widgetsApi.list().subscribe({
            next: (w) => this.existingWidgetIds.set(w.map((x) => x.id)),
            error: () => undefined,
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

    /** Switch the visualization — re-auto-assign channels for the new plugin's controls, then render.
     *  A view-bound plugin instead clears the mapping and loads its saved-view picker choices. */
    setVizType(type: string): void {
        this.vizType.set(type);
        const p = this.plugin();
        if (p?.meta.viewKind) {
            this.controls.set({});
            this.loadSavedViews(p.meta.viewKind);
            return;
        }
        this.controls.set(p ? autoAssignChannels(p, this.fields()) : {});
        this.run();
    }

    /** The saved views of a kind, as id/name picker choices (names only — no feature service needed). */
    private loadSavedViews(viewKind: string): void {
        this.componentsApi.list(viewKind as ComponentType).subscribe({
            next: (defs) => this.savedViews.set(defs.map((d) => ({ id: d.name, name: String(d.content['name'] ?? d.name) }))),
            error: () => this.toastr.warning('Could not load saved views.'),
        });
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
        this.boundQueryId.set(w.queryId);
        this.vizType.set(w.vizType);
        this.controls.set(w.controls);
        this.viewId.set(w.viewId ?? '');
        this.options.set(w.options ?? {});
        this.tags.set(w.tags);
        this.description.set(w.description);
        const viewKind = getViz(w.vizType)?.meta.viewKind;
        if (viewKind) {
            this.loadSavedViews(viewKind);
            return; // view-bound: no dataset to fetch, no query to run
        }
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
        if (!plugin || !ds || plugin.meta.viewKind) return;
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
        const viewBound = !!plugin?.meta.viewKind;
        if (!plugin || (viewBound ? !this.viewId() : !ds)) {
            this.toastr.warning(viewBound ? 'Pick a saved view first.' : 'Pick a dataset and a visualization first.');
            return;
        }
        this.dialog
            .open(WidgetSaveDialog, {
                data: {
                    suggestedId: this.id ?? `${viewBound ? this.viewId() : ds!.id}_${this.vizType()}`,
                    lockId: this.editing(),
                    tags: this.tags(),
                    description: this.description(),
                    existingNames: this.existingWidgetIds(),
                },
                width: '420px',
            })
            .afterClosed()
            .subscribe((result?: WidgetSaveResult) => {
                if (!result) return;
                const { name, tags, description } = result;
                const widget = buildWidget(name, viewBound ? '' : ds!.id, this.vizType(), viewBound ? {} : this.controls(), {
                    options: this.options(),
                    tags,
                    description,
                    viewId: viewBound ? this.viewId() : undefined,
                    queryId: viewBound ? undefined : this.boundQueryId(),
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
