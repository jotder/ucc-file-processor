import { ChangeDetectionStrategy, Component, Type, computed, effect, input, output, signal } from '@angular/core';
import { NgComponentOutlet } from '@angular/common';
import { ColDef } from 'ag-grid-community';
import { ChartData, ChartOptions, ChartType } from 'chart.js';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { CHART_CATEGORICAL, CHART_PALETTES, GAUGE_TRACK } from 'app/inspecto/theme/chart-tokens';
import { KpiComponent } from './plugins/kpi.component';
import { getVizComponentLoader } from './viz-components';
import { VizPlugin, VizProps, VizRenderOptions, VizSeries } from './viz-types';

/** componentKey → Angular component, for plugins that render via the escape hatch (`render.kind:'component'`).
 *  Only lightweight components belong here — heavy hosts register an async loader instead (`viz-components.ts`). */
const COMPONENT_BY_KEY: Record<string, Type<unknown>> = { kpi: KpiComponent };

/**
 * Render host — dispatches a {@link VizPlugin}'s `render.kind` to the right shared surface: `chartjs` →
 * `<inspecto-chart>`, `aggrid` → `<inspecto-data-table>`, `component` → `NgComponentOutlet` (KPI), `g6` →
 * placeholder for now. The plugins stay framework-free; this thin component is the only Angular glue.
 * `renderOptions` (a Studio Widget's advanced/cog config, or any caller's) applies uniformly across chartjs
 * plugins — palette, sort/limit, axis titles, legend, stacked — without each plugin needing to know about it.
 */
@Component({
    selector: 'inspecto-viz-render',
    standalone: true,
    imports: [NgComponentOutlet, InspectoChartComponent, DataTableComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @switch (renderKind()) {
            @case ('chartjs') {
                @if (chartData(); as data) {
                    <inspecto-chart [type]="chartType()" [data]="data" [options]="chartJsOptions()" (elementClick)="onElementClick($event)" />
                }
            }
            @case ('aggrid') {
                <inspecto-data-table tier="standard" [rows]="props().rows ?? []" [columns]="colDefs()" [sourceName]="title()" />
            }
            @case ('component') {
                @if (outletComponent(); as cmp) {
                    <div class="h-64">
                        <ng-container *ngComponentOutlet="cmp; inputs: outletInputs()" />
                    </div>
                }
            }
            @default {
                <div class="text-secondary p-4 text-sm">This visualization type isn't available yet.</div>
            }
        }
    `,
})
export class VizRenderComponent {
    readonly plugin = input.required<VizPlugin>();
    readonly props = input.required<VizProps>();
    /** Display/source name (table FROM, KPI caption). */
    readonly title = input('data');
    /** The advanced/cog render options (palette, sort/limit, axis titles, legend, stacked) — all optional. */
    readonly renderOptions = input<VizRenderOptions | undefined>(undefined);
    /** For view-bound plugins (`meta.viewKind`): the saved view id the outlet component renders. */
    readonly viewId = input<string | undefined>(undefined);
    /** Emits the clicked category's label (bar/line/area/pie/bubble) — the drill-down seam. Gauge has no
     *  filterable categories, so it never emits. */
    readonly categoryClick = output<string>();

    readonly renderKind = computed(() => this.plugin().render.kind);

    /** A lazily-loaded component-render host (from a registered loader), once its import resolves. */
    private readonly loadedComponent = signal<Type<unknown> | null>(null);

    /** The Angular component for a `component`-render plugin — static map first, then the loader registry. */
    readonly outletComponent = computed<Type<unknown> | null>(() => {
        const r = this.plugin().render;
        return r.kind === 'component' ? COMPONENT_BY_KEY[r.componentKey] ?? this.loadedComponent() : null;
    });

    constructor() {
        // Resolve a registered async loader when the plugin needs one (keeps MapLibre/G6 out of eager bundles).
        // The stale-key guard drops a resolution that lands after the plugin has already changed.
        effect(() => {
            const r = this.plugin().render;
            if (r.kind !== 'component' || COMPONENT_BY_KEY[r.componentKey]) return;
            const loader = getVizComponentLoader(r.componentKey);
            this.loadedComponent.set(null);
            if (!loader) return;
            const key = r.componentKey;
            loader().then((cmp) => {
                const current = this.plugin().render;
                if (current.kind === 'component' && current.componentKey === key) this.loadedComponent.set(cmp);
            });
        });
    }

    readonly chartType = computed<ChartType>(() => {
        const r = this.plugin().render;
        return (r.kind === 'chartjs' ? r.chartType : 'bar') as ChartType;
    });

    /** Props reordered by `sort` (on the first series' value) and trimmed to `limit` categories. Chart.js only —
     *  table/KPI ignore sort/limit (rows already have their own grid sort; KPI has no categories). */
    private readonly sortedProps = computed<VizProps>(() => {
        const p = this.props();
        const opts = this.renderOptions();
        if (!opts?.sort && !opts?.limit) return p;
        let order = p.labels.map((_, i) => i);
        if (opts.sort) {
            const dir = opts.sort === 'asc' ? 1 : -1;
            const value = (i: number): number => p.series[0]?.data[i] ?? 0;
            order = [...order].sort((a, b) => dir * (value(a) - value(b)));
        }
        if (opts.limit) order = order.slice(0, opts.limit);
        return {
            ...p,
            labels: order.map((i) => p.labels[i]),
            series: p.series.map((s): VizSeries => ({ ...s, data: order.map((i) => s.data[i]) })),
        };
    });

    readonly chartData = computed<ChartData | null>(() => {
        const plugin = this.plugin();
        const r = plugin.render;
        if (r.kind !== 'chartjs') return null;
        const p = this.sortedProps();
        const palette = CHART_PALETTES[this.renderOptions()?.palette ?? ''] ?? CHART_CATEGORICAL;
        const color = (i: number): string => palette[i % palette.length];

        if (plugin.meta.type === 'gauge') {
            const value = Math.max(0, Math.min(100, this.props().value ?? 0));
            return { labels: ['Value', 'Remaining'], datasets: [{ data: [value, 100 - value], backgroundColor: [color(0), GAUGE_TRACK] }] };
        }
        if (plugin.meta.type === 'scatter') {
            const [xs, ys] = p.series;
            const points = (xs?.data ?? []).map((x, i) => ({ x, y: ys?.data[i] ?? 0 }));
            return { labels: p.labels, datasets: [{ label: 'Scatter', data: points, backgroundColor: p.labels.map((_, i) => color(i)) }] };
        }
        if (plugin.meta.type === 'bubble') {
            const [xs, ys, sizes] = p.series;
            const maxSize = Math.max(1, ...(sizes?.data ?? [0]));
            const toRadius = (v: number): number => 4 + (Math.max(0, v) / maxSize) * 20; // 4–24px, relative to the largest point
            const points = (xs?.data ?? []).map((x, i) => ({ x, y: ys?.data[i] ?? 0, r: toRadius(sizes?.data[i] ?? 0) }));
            return { labels: p.labels, datasets: [{ label: 'Bubble', data: points, backgroundColor: p.labels.map((_, i) => color(i)) }] };
        }

        const isPie = r.chartType === 'pie' || r.chartType === 'doughnut';
        if (isPie) {
            return {
                labels: p.labels,
                datasets: [{ data: p.series[0]?.data ?? [], backgroundColor: p.labels.map((_, i) => color(i)) }],
            };
        }
        return {
            labels: p.labels,
            datasets: p.series.map((s, i) => ({
                label: s.label,
                data: s.data,
                backgroundColor: color(i),
                borderColor: color(i),
                fill: (s['fill'] as boolean) ?? false,
                stack: this.renderOptions()?.stacked ? 'stack' : undefined,
            })),
        };
    });

    /** Chart.js overrides derived from `renderOptions` — legend show/position, axis titles, stacked scales —
     *  plus the gauge's fixed half-circle styling (an explicit `renderOptions.legend` still overrides its
     *  hidden-by-default legend). `<inspecto-chart>` deep-merges these under its theme defaults, so omitted
     *  fields keep their styling. */
    readonly chartJsOptions = computed<ChartOptions>(() => {
        const isGauge = this.plugin().meta.type === 'gauge';
        const opts = this.renderOptions();
        const legend = opts?.legend;
        const pluginsOverride = legend
            ? { legend: { display: legend.show ?? true, position: legend.position ?? 'top' } }
            : isGauge
              ? { legend: { display: false }, tooltip: { enabled: false } }
              : undefined;
        const axis = opts?.axis;
        const stacked = opts?.stacked;
        const isFunnel = this.plugin().meta.type === 'funnel';
        return {
            ...(isGauge ? { circumference: 180, rotation: 270, cutout: '70%' } : {}),
            ...(isFunnel ? { indexAxis: 'y' as const } : {}),
            plugins: pluginsOverride,
            scales:
                axis?.xTitle || axis?.yTitle || stacked
                    ? {
                          x: { stacked: !!stacked, title: axis?.xTitle ? { display: true, text: axis.xTitle } : undefined },
                          y: { stacked: !!stacked, title: axis?.yTitle ? { display: true, text: axis.yTitle } : undefined },
                      }
                    : undefined,
        };
    });

    readonly colDefs = computed<ColDef[] | undefined>(() => {
        const cols = this.props().columns;
        return cols?.length ? cols.map((f) => ({ field: f })) : undefined;
    });

    /** Inputs for the outlet component — the KPI's value/label, or a view-bound wrapper's saved-view id. */
    readonly outletInputs = computed<Record<string, unknown>>(() => {
        const r = this.plugin().render;
        return r.kind === 'component' && r.componentKey === 'kpi'
            ? { value: this.props().value ?? 0, label: this.title() }
            : { viewId: this.viewId() };
    });

    /** Resolve the clicked point's index to its category label (from the same, possibly sorted/limited,
     *  labels the chart actually rendered) and emit it — skipped for gauge, whose slices aren't categories. */
    onElementClick(index: number): void {
        if (this.plugin().meta.type === 'gauge') return;
        const label = this.sortedProps().labels[index];
        if (label != null) this.categoryClick.emit(label);
    }
}
