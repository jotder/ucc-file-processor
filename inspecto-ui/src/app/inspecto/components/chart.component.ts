import {
    AfterViewInit,
    Component,
    DestroyRef,
    ElementRef,
    EventEmitter,
    Input,
    OnChanges,
    OnDestroy,
    Output,
    ViewChild,
    inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { GammaConfigService } from '@gamma/services/config';
import { Chart, ChartConfiguration, ChartData, ChartOptions, ChartType, registerables } from 'chart.js';
import { canvasTheme } from 'app/inspecto/theme/chart-tokens';

Chart.register(...registerables);

/** One data point as alt-text: numbers as-is, `{x,y}` points as a pair, anything else stringified. */
function summarize(v: unknown): string {
    if (v == null) return '—';
    if (typeof v === 'object') {
        const p = v as { x?: unknown; y?: unknown };
        if (p.x !== undefined || p.y !== undefined) return `(${String(p.x ?? '—')}, ${String(p.y ?? '—')})`;
    }
    return String(v);
}

/**
 * Thin theme-aware Chart.js host. Recreates the chart when data changes and
 * restyles axis/legend colors when the gamma scheme flips between light/dark.
 */
@Component({
    selector: 'inspecto-chart',
    standalone: true,
    template: '<canvas #canvas role="img" [attr.aria-label]="altText"></canvas>',
    host: { class: 'block relative h-64 w-full' },
})
export class InspectoChartComponent implements AfterViewInit, OnChanges, OnDestroy {
    @Input({ required: true }) type: ChartType = 'bar';
    @Input({ required: true }) data: ChartData | null = null;
    @Input() options: ChartOptions = {};
    /** The clicked data point's index, for drill-down — no-op cost when nobody listens. */
    @Output() elementClick = new EventEmitter<number>();

    /** Text alternative for the canvas (WCAG 1.1.1 — canvases are invisible to screen readers): the chart
     *  type plus each category's first-series value, capped so long series don't produce an essay. */
    get altText(): string {
        const d = this.data;
        if (!d?.labels?.length) return `${this.type} chart`;
        const values = (d.datasets?.[0]?.data ?? []) as unknown[];
        const parts = d.labels.slice(0, 10).map((l, i) => `${String(l)}: ${summarize(values[i])}`);
        const more = d.labels.length > 10 ? `, and ${d.labels.length - 10} more` : '';
        return `${this.type} chart. ${parts.join(', ')}${more}.`;
    }

    @ViewChild('canvas') private canvas!: ElementRef<HTMLCanvasElement>;
    private chart: Chart | null = null;
    private dark = false;
    private ready = false;
    private destroyRef = inject(DestroyRef);

    constructor() {
        inject(GammaConfigService)
            .config$.pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((config) => {
                this.dark =
                    config?.scheme === 'dark' ||
                    (config?.scheme === 'auto' &&
                        window.matchMedia('(prefers-color-scheme: dark)').matches);
                if (this.ready) this.rebuild();
            });
    }

    ngAfterViewInit(): void {
        this.ready = true;
        this.rebuild();
    }

    ngOnChanges(): void {
        if (this.ready) this.rebuild();
    }

    ngOnDestroy(): void {
        this.chart?.destroy();
    }

    private rebuild(): void {
        this.chart?.destroy();
        this.chart = null;
        if (!this.data) return;
        const { fg, grid } = canvasTheme(this.dark);
        // Deep-merge (not replace) legend/scales so a caller's override (e.g. Widget renderOptions — legend
        // position, an axis title, stacked) composes with the theme's fg/grid colors instead of losing them.
        const axisDefaults = this.type === 'bar' ? { ticks: { color: fg }, grid: { color: grid } } : {};
        const config: ChartConfiguration = {
            type: this.type,
            data: this.data,
            options: {
                responsive: true,
                maintainAspectRatio: false,
                onClick: (_event, elements) => {
                    const index = elements[0]?.index;
                    if (index != null) this.elementClick.emit(index);
                },
                ...this.options,
                plugins: {
                    ...this.options.plugins,
                    legend: { labels: { color: fg }, ...this.options.plugins?.legend },
                },
                scales:
                    this.type === 'bar' || this.options.scales
                        ? {
                              x: { ...axisDefaults, ...this.options.scales?.['x'] },
                              y: { ...axisDefaults, ...this.options.scales?.['y'] },
                          }
                        : undefined,
            },
        };
        this.chart = new Chart(this.canvas.nativeElement, config);
    }
}
