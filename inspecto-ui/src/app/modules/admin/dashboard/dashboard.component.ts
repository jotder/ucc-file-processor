import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ChartData } from 'chart.js';
import { ToastrService } from 'ngx-toastr';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
    AcquisitionMetrics,
    AcquisitionMetricsService,
    DEFAULT_REFRESH_MS,
    EventRow,
    EventsService,
    HealthService,
    ReadyStatus,
    ReportsService,
    RunStatus,
    ServiceReport,
    StatusReport,
    visibleInterval,
} from 'app/inspecto/api';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent, statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';
import { fmtBytes, fmtInt, fmtPercent } from 'app/inspecto/format';
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
        MatSlideToggleModule,
        MatTooltipModule,
        InspectoChartComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
        DataTableComponent,
    ],
    templateUrl: './dashboard.component.html',
})
export class DashboardComponent implements OnInit {
    private health = inject(HealthService);
    private reports = inject(ReportsService);
    private acqApi = inject(AcquisitionMetricsService);
    private eventsApi = inject(EventsService);
    private toastr = inject(ToastrService);
    private destroyRef = inject(DestroyRef);

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

    readonly pipelineColumns: ColDef<RunStatus>[] = [
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        {
            field: 'paused',
            headerName: 'Paused',
            width: 110,
            cellRenderer: (p: ICellRendererParams<RunStatus>) => statusBadgeHtml(p.value ? 'paused' : 'active'),
        },
        { field: 'committedBatches', headerName: 'Committed', width: 130 },
        { field: 'quarantineFiles', headerName: 'Quarantine', width: 130 },
        { field: 'lastBatchId', headerName: 'Last batch', flex: 1 },
        {
            field: 'lastBatchStatus',
            headerName: 'Last status',
            width: 140,
            cellRenderer: (p: ICellRendererParams<RunStatus>) => (p.value ? statusBadgeHtml(p.value) : '—'),
        },
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
        // Each source degrades independently — a single failing endpoint shouldn't blank the whole
        // dashboard. forkJoin then always completes; we warn only if every core call failed.
        forkJoin({
            ready: this.health.ready().pipe(catchError(() => of(null))),
            status: this.reports.status().pipe(catchError(() => of(null))),
            report: this.reports.serviceReport().pipe(catchError(() => of(null))),
            metrics: this.health.metrics().pipe(catchError(() => of(null))),
        }).subscribe(({ ready, status, report, metrics }) => {
            this.ready = ready;
            this.status = status;
            this.report = report;
            this.metricsText = metrics ?? '';
            if (report) {
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
            }
            this.loading = false;
            if (!ready && !status && !report && metrics === null) {
                this.toastr.error('Failed to load service status. The backend may be unreachable.');
            }
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
        return this.report ? fmtPercent(this.report.errorRate) : '—';
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
            { label: 'Files discovered', value: fmtInt(discovered) },
            { label: 'Files downloaded', value: fmtInt(downloaded) },
            { label: 'Downloads failed', value: fmtInt(failed) },
            { label: 'Bytes transferred', value: fmtBytes(bytes) },
        ];
    }

    /** Short clock time for the activity feed. */
    fmtTime(ts: number): string {
        return new Date(ts).toLocaleTimeString();
    }
}
