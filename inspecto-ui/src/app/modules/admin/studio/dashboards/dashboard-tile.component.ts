import { ChangeDetectionStrategy, Component, computed, effect, input, signal } from '@angular/core';
import { ColumnMeta, ConditionGroup } from 'app/inspecto/query';
import { VizPlugin, VizProps, getViz, runSpec } from 'app/inspecto/viz';
import { VizRenderComponent } from 'app/inspecto/viz/viz-render.component';
import { Chart } from '../charts/chart-types';
import { Dataset } from '../datasets/dataset-types';
import { SAMPLE_SOURCES } from '../datasets/dataset-sources';

/**
 * One live dashboard tile — renders a saved {@link Chart} via `viz-render`, with the dashboard's cross-filter
 * injected into the chart's QuerySpec. Presentational: the host resolves the chart + its dataset and passes
 * them in; the tile compiles + runs offline (AlaSQL) and re-renders reactively when the filter changes.
 */
@Component({
    selector: 'app-dashboard-tile',
    standalone: true,
    imports: [VizRenderComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="bg-card flex h-full flex-col rounded-2xl p-4 shadow">
            <div class="mb-2 text-sm font-semibold">{{ chart().name }}</div>
            @if (plugin(); as p) {
                <inspecto-viz-render [plugin]="p" [props]="props()" [title]="dataset().sourceName" />
            } @else {
                <div class="text-secondary text-sm">Unknown visualization “{{ chart().vizType }}”.</div>
            }
        </div>
    `,
})
export class DashboardTileComponent {
    readonly chart = input.required<Chart>();
    readonly dataset = input.required<Dataset>();
    readonly filter = input<ConditionGroup | null>(null);

    readonly plugin = computed<VizPlugin | null>(() => getViz(this.chart().vizType) ?? null);
    readonly props = signal<VizProps>({ labels: [], series: [] });

    private readonly colMetas = computed<ColumnMeta[]>(() =>
        this.dataset().columns.map((c) => ({ name: c.name, type: c.type })),
    );

    constructor() {
        effect(() => {
            const plugin = this.plugin();
            const ds = this.dataset();
            const chart = this.chart();
            if (!plugin) return;
            const spec = plugin.buildQuery(chart.controls, {
                datasetId: ds.id,
                sourceName: ds.sourceName,
                filters: this.filter(),
            });
            runSpec(spec, SAMPLE_SOURCES[ds.sourceName] ?? [], this.colMetas())
                .then((res) => this.props.set(plugin.transformProps(res.ok ? res.rows : [], chart.controls)))
                .catch(() => this.props.set({ labels: [], series: [] }));
        });
    }
}
