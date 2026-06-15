import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
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
    AcquisitionMetrics,
    AcquisitionMetricsService,
    DEFAULT_REFRESH_MS,
    EventRow,
    EventsService,
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
        RouterLink,
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
    private acqApi = inject(AcquisitionMetricsService);
    private eventsApi = inject(EventsService);
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

    /** Compact acquisition KPIs (empty when the deployment has no acquisition activity). */
    acqCards: { label: string; value: string }[] = [];
    /** Newest few events for the activity feed (GET /events/search?limit=8). */
    recentEvents: EventRow[] = [];

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

        // Acquisition summary + recent activity — fetched independently so a failure (or a
        // deployment without these engines) degrades gracefully rather than breaking the dashboard.
        this.acqApi.get().subscribe({
            next: (m) => this.buildAcq(m),
            error: () => (this.acqCards = []),
        });
        this.eventsApi.search({ limit: 8 }).subscribe({
            next: (e) => (this.recentEvents = e),
            error: () => (this.recentEvents = []),
        });
    }

    get errorRatePct(): string {
        return this.report ? (this.report.errorRate * 100).toFixed(1) + '%' : '—';
    }

    private total(m: AcquisitionMetrics, name: string): number {
        return (m[name]?.series ?? []).reduce((acc, s) => acc + (s.value ?? s.sum ?? 0), 0);
    }

    private buildAcq(m: AcquisitionMetrics): void {
        const discovered = this.total(m, 'inspecto_files_discovered_total');
        const downloaded = this.total(m, 'inspecto_files_downloaded_total');
        const failed = this.total(m, 'inspecto_downloads_failed_total');
        const bytes = this.total(m, 'inspecto_bytes_transferred_total');
        if (discovered + downloaded + failed + bytes === 0) {
            this.acqCards = []; // no acquisition activity — hide the row
            return;
        }
        this.acqCards = [
            { label: 'Files discovered', value: this.fmtInt(discovered) },
            { label: 'Files downloaded', value: this.fmtInt(downloaded) },
            { label: 'Downloads failed', value: this.fmtInt(failed) },
            { label: 'Bytes transferred', value: this.fmtBytes(bytes) },
        ];
    }

    private fmtInt(n: number): string {
        return Math.round(n).toLocaleString();
    }

    private fmtBytes(n: number): string {
        if (n < 1024) return `${Math.round(n)} B`;
        const units = ['KB', 'MB', 'GB', 'TB'];
        let v = n / 1024;
        let i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return `${v.toFixed(1)} ${units[i]}`;
    }

    /** Short clock time for the activity feed. */
    fmtTime(ts: number): string {
        return new Date(ts).toLocaleTimeString();
    }

    /** Tailwind badge classes per event level (literal strings so the JIT scanner keeps them). */
    levelClass(level: string): string {
        switch (level) {
            case 'ERROR':
                return 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200';
            case 'WARN':
                return 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200';
            case 'INFO':
                return 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200';
            default:
                return 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-200';
        }
    }
}
