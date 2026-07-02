import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColumnMeta, ConditionGroup } from 'app/inspecto/query';
import { VizPlugin, VizProps, bucketRows, getViz } from 'app/inspecto/viz';
import { DatasetResultService } from 'app/inspecto/viz/dataset-result.service';
import { VizRenderComponent } from 'app/inspecto/viz/viz-render.component';
import { Widget } from './widget-types';
import { WidgetsService } from './widgets.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { SAMPLE_SOURCES } from '../datasets/dataset-sources';

/** A drilled-down click: filter `field = value` on whatever consumes this widget's data (a dashboard). */
export interface DrillEvent {
    field: string;
    value: string;
}

/**
 * The one render path for a saved {@link Widget} — powers dashboard tiles, the gallery's thumbnails, and the
 * standalone `/studio/widgets/:id/view` route, so none of them duplicate the query-run/render pipeline.
 *
 * Two ways to supply the widget: a caller that already has it loaded (a dashboard editor resolving many
 * tiles against one `WidgetsService.list()` call) passes `[widget]`/`[dataset]` directly — no extra fetch.
 * A caller with only an id (the standalone route) passes `[widgetId]` and this component fetches both itself.
 *
 * Also the drill-down + export seam: a category click on a chartjs render re-emits as `(drill)` with the
 * clicked value's *field* (resolved from the widget's own channel mapping, not just the label) so a listening
 * dashboard can toggle a matching filter; the export button downloads the rendered `<canvas>` as a PNG.
 */
@Component({
    selector: 'app-widget-host',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule, VizRenderComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="bg-card flex h-full flex-col rounded-2xl p-4 shadow">
            @if (resolvedWidget(); as widget) {
                <div class="mb-0.5 flex items-start justify-between gap-2">
                    <div class="min-w-0">
                        <div class="truncate text-sm font-semibold">{{ widget.options?.title || widget.name }}</div>
                        @if (widget.options?.subtitle) { <div class="text-secondary text-xs">{{ widget.options?.subtitle }}</div> }
                    </div>
                    @if (canExport()) {
                        <button mat-icon-button (click)="exportPng()" matTooltip="Export as PNG" aria-label="Export as PNG">
                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:download"></mat-icon>
                        </button>
                    }
                </div>
                @if (plugin(); as p) {
                    @if (resolvedDataset(); as dataset) {
                        <inspecto-viz-render
                            [plugin]="p"
                            [props]="props()"
                            [title]="dataset.sourceName"
                            [renderOptions]="widget.options"
                            (categoryClick)="onCategoryClick($event)"
                        />
                    }
                } @else {
                    <div class="text-secondary text-sm">Unknown visualization “{{ widget.vizType }}”.</div>
                }
            } @else {
                <div class="text-secondary flex h-32 items-center justify-center text-sm">Loading…</div>
            }
        </div>
    `,
})
export class WidgetHostComponent {
    private widgetsApi = inject(WidgetsService);
    private datasetsApi = inject(DatasetsService);
    private datasetResult = inject(DatasetResultService);
    private elementRef = inject(ElementRef<HTMLElement>);

    /** Self-fetch mode: only an id (the standalone route). */
    readonly widgetId = input<string | undefined>(undefined);
    /** Pre-loaded mode: caller already has the widget + dataset (dashboard tiles, the gallery). */
    readonly widget = input<Widget | undefined>(undefined);
    readonly dataset = input<Dataset | undefined>(undefined);
    readonly filter = input<ConditionGroup | null>(null);
    /** A category click, resolved to the field it should filter on (drill-down). */
    readonly drill = output<DrillEvent>();

    private readonly fetchedWidget = signal<Widget | undefined>(undefined);
    private readonly fetchedDataset = signal<Dataset | undefined>(undefined);

    readonly resolvedWidget = computed(() => this.widget() ?? this.fetchedWidget());
    readonly resolvedDataset = computed(() => this.dataset() ?? this.fetchedDataset());

    readonly plugin = computed<VizPlugin | null>(() => {
        const w = this.resolvedWidget();
        return w ? getViz(w.vizType) ?? null : null;
    });
    readonly props = signal<VizProps>({ labels: [], series: [] });
    readonly canExport = computed(() => this.plugin()?.render.kind === 'chartjs');

    private readonly colMetas = computed<ColumnMeta[]>(() =>
        (this.resolvedDataset()?.columns ?? []).map((c) => ({ name: c.name, type: c.type })),
    );

    constructor() {
        // Self-fetch: an id but no pre-loaded widget — fetch the widget, then its dataset.
        effect(() => {
            const id = this.widgetId();
            if (!id || this.widget()) return;
            this.widgetsApi.get(id).subscribe({ next: (w) => this.fetchedWidget.set(w), error: () => undefined });
        });
        effect(() => {
            const w = this.resolvedWidget();
            if (!w || this.dataset()) return;
            this.datasetsApi.get(w.datasetId).subscribe({ next: (d) => this.fetchedDataset.set(d), error: () => undefined });
        });

        // Run the query whenever the resolved widget/dataset/filter change — deduped: two hosts (e.g. two
        // dashboard tiles) computing the identical spec share one run via DatasetResultService.
        effect(() => {
            const plugin = this.plugin();
            const widget = this.resolvedWidget();
            const dataset = this.resolvedDataset();
            if (!plugin || !widget || !dataset) return;
            const spec = plugin.buildQuery(widget.controls, {
                datasetId: dataset.id,
                sourceName: dataset.sourceName,
                filters: this.filter(),
            });
            const x = widget.controls.x?.[0];
            const rows = x ? bucketRows(SAMPLE_SOURCES[dataset.sourceName] ?? [], x.field, x.grain) : SAMPLE_SOURCES[dataset.sourceName] ?? [];
            this.datasetResult
                .run(spec, rows, this.colMetas())
                .then((res) => this.props.set(plugin.transformProps(res.ok ? res.rows : [], widget.controls)))
                .catch(() => this.props.set({ labels: [], series: [] }));
        });
    }

    /** Resolve the clicked category to the field it came from (the widget's `x` channel, or `series` for
     *  plugins that use it as the point label, e.g. bubble) and emit the drill event. */
    onCategoryClick(value: string): void {
        const controls = this.resolvedWidget()?.controls;
        const field = controls?.x?.[0]?.field ?? controls?.series?.[0]?.field;
        if (field) this.drill.emit({ field, value });
    }

    exportPng(): void {
        const canvas = this.elementRef.nativeElement.querySelector('canvas');
        if (!canvas) return;
        const name = this.resolvedWidget()?.id ?? 'widget';
        const link = document.createElement('a');
        link.href = canvas.toDataURL('image/png');
        link.download = `${name}.png`;
        link.click();
    }
}
