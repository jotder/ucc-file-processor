import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef } from 'ag-grid-community';
import { ChartData } from 'chart.js';
import { forkJoin } from 'rxjs';
import {
    DEFAULT_REFRESH_MS,
    HealthService,
    ReadyStatus,
    ReportsService,
    ServiceReport,
    StatusReport,
    visibleInterval,
} from 'app/inspecto/api';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { INSPECTO_DEFAULT_COL_DEF, InspectoGridThemeService, fmtDateTime } from 'app/inspecto/grid';
import { CHART_SERIES } from 'app/inspecto/theme/chart-tokens';

/**
 * Inspector dashboard — service health, throughput and error-rate overview.
 * Consumes GET /ready, /status, /report and /metrics (raw Prometheus text as fallback).
 */
@Component({
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatSlideToggleModule,
        MatTooltipModule,
        AgGridAngular,
        InspectoChartComponent,
    ],
    templateUrl: './dashboard.component.html',
})
export class DashboardComponent implements OnInit {
    private health = inject(HealthService);
    private reports = inject(ReportsService);
    private destroyRef = inject(DestroyRef);
    readonly gridTheme = inject(InspectoGridThemeService);
    readonly defaultColDef = INSPECTO_DEFAULT_COL_DEF;

    autoRefresh = true;
    loading = true;
    ready: ReadyStatus | null = null;
    status: StatusReport | null = null;
    report: ServiceReport | null = null;
    metricsText = '';
    showMetrics = false;

    latencyData: ChartData | null = null;
    outcomeData: ChartData | null = null;

    readonly pipelineColumns: ColDef[] = [
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        { field: 'paused', headerName: 'Paused', width: 110 },
        { field: 'committedBatches', headerName: 'Committed', width: 130 },
        { field: 'quarantineFiles', headerName: 'Quarantine', width: 130 },
        { field: 'lastBatchId', headerName: 'Last batch', flex: 1 },
        { field: 'lastBatchStatus', headerName: 'Last status', width: 140 },
        { field: 'lastBatchTime', headerName: 'Last time', width: 180, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    ngOnInit(): void {
        this.refresh();
        visibleInterval(DEFAULT_REFRESH_MS)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => {
                if (this.autoRefresh) this.refresh();
            });
    }

    refresh(): void {
        this.loading = true;
        forkJoin({
            ready: this.health.ready(),
            status: this.reports.status(),
            report: this.reports.serviceReport(),
            metrics: this.health.metrics(),
        }).subscribe({
            next: ({ ready, status, report, metrics }) => {
                this.ready = ready;
                this.status = status;
                this.report = report;
                this.metricsText = metrics;
                this.latencyData = {
                    labels: ['p50', 'p95', 'p99'],
                    datasets: [
                        {
                            label: 'duration (ms)',
                            data: [report.p50DurationMs, report.p95DurationMs, report.p99DurationMs],
                            backgroundColor: CHART_SERIES.primary,
                        },
                    ],
                };
                this.outcomeData = {
                    labels: ['Success', 'Failed'],
                    datasets: [
                        {
                            data: [report.success, report.failed],
                            backgroundColor: [CHART_SERIES.success, CHART_SERIES.error],
                        },
                    ],
                };
                this.loading = false;
            },
            error: () => {
                this.loading = false;
            },
        });
    }

    get errorRatePct(): string {
        return this.report ? (this.report.errorRate * 100).toFixed(1) + '%' : '—';
    }
}
