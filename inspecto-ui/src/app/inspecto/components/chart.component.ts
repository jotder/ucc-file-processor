import {
    AfterViewInit,
    Component,
    DestroyRef,
    ElementRef,
    Input,
    OnChanges,
    OnDestroy,
    ViewChild,
    inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { GammaConfigService } from '@gamma/services/config';
import { Chart, ChartConfiguration, ChartData, ChartOptions, ChartType, registerables } from 'chart.js';
import { canvasTheme } from 'app/inspecto/theme/chart-tokens';

Chart.register(...registerables);

/**
 * Thin theme-aware Chart.js host. Recreates the chart when data changes and
 * restyles axis/legend colors when the gamma scheme flips between light/dark.
 */
@Component({
    selector: 'inspecto-chart',
    standalone: true,
    template: '<canvas #canvas></canvas>',
    host: { class: 'block relative h-64 w-full' },
})
export class InspectoChartComponent implements AfterViewInit, OnChanges, OnDestroy {
    @Input({ required: true }) type: ChartType = 'bar';
    @Input({ required: true }) data: ChartData | null = null;
    @Input() options: ChartOptions = {};

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
        const config: ChartConfiguration = {
            type: this.type,
            data: this.data,
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { labels: { color: fg } },
                    ...this.options.plugins,
                },
                scales:
                    this.type === 'bar'
                        ? {
                              x: { ticks: { color: fg }, grid: { color: grid } },
                              y: { ticks: { color: fg }, grid: { color: grid } },
                          }
                        : undefined,
                ...this.options,
            },
        };
        this.chart = new Chart(this.canvas.nativeElement, config);
    }
}
