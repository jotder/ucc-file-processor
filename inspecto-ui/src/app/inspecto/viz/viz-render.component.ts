import { ChangeDetectionStrategy, Component, Type, computed, input } from '@angular/core';
import { NgComponentOutlet } from '@angular/common';
import { ColDef } from 'ag-grid-community';
import { ChartData, ChartType } from 'chart.js';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { CHART_CATEGORICAL } from 'app/inspecto/theme/chart-tokens';
import { KpiComponent } from './plugins/kpi.component';
import { VizPlugin, VizProps } from './viz-types';

/** componentKey → Angular component, for plugins that render via the escape hatch (`render.kind:'component'`). */
const COMPONENT_BY_KEY: Record<string, Type<unknown>> = { kpi: KpiComponent };

/**
 * Render host — dispatches a {@link VizPlugin}'s `render.kind` to the right shared surface: `chartjs` →
 * `<inspecto-chart>`, `aggrid` → `<inspecto-data-table>`, `component` → `NgComponentOutlet` (KPI), `g6` →
 * placeholder for now. The plugins stay framework-free; this thin component is the only Angular glue.
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
                    <inspecto-chart [type]="chartType()" [data]="data" />
                }
            }
            @case ('aggrid') {
                <inspecto-data-table tier="standard" [rows]="props().rows ?? []" [columns]="colDefs()" [sourceName]="title()" />
            }
            @case ('component') {
                @if (outletComponent(); as cmp) {
                    <div class="h-64">
                        <ng-container *ngComponentOutlet="cmp; inputs: kpiInputs()" />
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

    readonly renderKind = computed(() => this.plugin().render.kind);

    /** The Angular component for a `component`-render plugin, resolved from its `componentKey`. */
    readonly outletComponent = computed<Type<unknown> | null>(() => {
        const r = this.plugin().render;
        return r.kind === 'component' ? COMPONENT_BY_KEY[r.componentKey] ?? null : null;
    });

    readonly chartType = computed<ChartType>(() => {
        const r = this.plugin().render;
        return (r.kind === 'chartjs' ? r.chartType : 'bar') as ChartType;
    });

    readonly chartData = computed<ChartData | null>(() => {
        const r = this.plugin().render;
        if (r.kind !== 'chartjs') return null;
        const p = this.props();
        const isPie = r.chartType === 'pie' || r.chartType === 'doughnut';
        const color = (i: number): string => CHART_CATEGORICAL[i % CHART_CATEGORICAL.length];
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
            })),
        };
    });

    readonly colDefs = computed<ColDef[] | undefined>(() => {
        const cols = this.props().columns;
        return cols?.length ? cols.map((f) => ({ field: f })) : undefined;
    });

    readonly kpiInputs = computed(() => ({ value: this.props().value ?? 0, label: this.title() }));
}
