import { Component, inject, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ChartData } from 'chart.js';
import { Subscription, forkJoin } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    JobFailureDay,
    JobMetrics,
    JobRunRow,
    JobsService,
    JobView,
    visibleInterval,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { AssistDialog } from 'app/inspecto/components/assist.dialog';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { CHART_SERIES } from 'app/inspecto/theme/chart-tokens';
import { JobRunsDialog } from './job-runs.dialog';
import { JobRunDetailDialog } from './job-run-detail.dialog';

/** Which lens the Jobs pane shows: the schedule registry, or execution reporting over the run history. */
export type JobsViewMode = 'schedules' | 'reporting';

/** Live-tail poll cadence (ms) for the reporting view — pauses while the tab is hidden. */
const LIVE_TAIL_MS = 5000;

/** Format a duration in ms for display (e.g. `450ms`, `1.2s`, `2m 03s`). */
export function fmtDuration(ms: number | undefined | null): string {
    if (ms == null) return '—';
    if (ms < 1000) return `${ms}ms`;
    const s = ms / 1000;
    if (s < 60) return `${s.toFixed(1)}s`;
    const m = Math.floor(s / 60);
    return `${m}m ${String(Math.round(s % 60)).padStart(2, '0')}s`;
}

/**
 * Jobs & schedules — two lenses over the same job layer:
 * <ul>
 *   <li><b>Schedules</b> — every registered cron / event / manual job with last-status, next-fire,
 *       trigger-now and per-job run history (the management view).</li>
 *   <li><b>Reporting</b> (T27) — execution analytics over the durable DuckDB run projection: metric
 *       cards (success rate, p50 / p95 duration), a failure-trend bar, and a durable run-history grid.
 *       Requires the DuckDB backend (<code>-Djobs.backend=duckdb</code>); otherwise the endpoints 404
 *       and an informative empty state is shown.</li>
 * </ul>
 */
@Component({
    selector: 'app-jobs',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatSlideToggleModule,
        MatTooltipModule,
        DataTableComponent,
        InspectoChartComponent,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './jobs.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class JobsComponent implements OnInit, OnDestroy {
    private api = inject(JobsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);

    mode: JobsViewMode = 'schedules';

    // ── schedules view ───────────────────────────────────────────────────────────
    jobs: JobView[] = [];
    loading = false;
    unavailable = false;

    // ── reporting view (T27) ─────────────────────────────────────────────────────
    metrics: JobMetrics | null = null;
    runs: JobRunRow[] = [];
    chartData: ChartData | null = null;
    reportLoaded = false;
    reportLoading = false;
    /** True when the reporting backend is off (endpoints 404) — distinct from a transient failure. */
    reportDisabled = false;
    live = false;
    private liveSub?: Subscription;

    fJob = '';
    fLimit = 100;
    fDays = 30;
    readonly limitOptions = [50, 100, 250, 500];

    readonly fmtDuration = fmtDuration;

    openSchedule(): void {
        this.dialog.open(AssistDialog, {
            data: {
                title: 'New schedule — describe it in plain English',
                intent: 'nl-to-schedule',
                placeholder: 'e.g. run the daily-roaming ingest every weekday at 6am',
            },
            width: '680px',
            maxHeight: '85vh',
        });
    }

    readonly columnDefs: ColDef<JobView>[] = [
        { field: 'name', headerName: 'Job', flex: 1 },
        { field: 'type', headerName: 'Type', width: 110 },
        { field: 'cron', headerName: 'Cron', flex: 1 },
        { field: 'onPipeline', headerName: 'On pipeline', flex: 1 },
        { field: 'enabled', headerName: 'Enabled', width: 100 },
        { field: 'lastStatus', headerName: 'Last status', width: 120 },
        { field: 'lastRunTime', headerName: 'Last run', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'nextFire', headerName: 'Next fire', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    readonly scheduleActions: InspectoRowAction<JobView>[] = [
        {
            icon: 'heroicons_outline:play',
            hint: 'Run now',
            onClick: (j) => this.trigger(j),
        },
        {
            icon: 'heroicons_outline:list-bullet',
            hint: 'Run history',
            onClick: (j) => this.openRuns(j),
        },
    ];

    /** Reporting grid: the durable run history (newest first) from the DuckDB projection. */
    readonly reportColumnDefs: ColDef<JobRunRow>[] = [
        { field: 'startTime', headerName: 'Started', width: 180, valueFormatter: (p) => p.value ?? '—' },
        { field: 'job', headerName: 'Job', width: 160 },
        { field: 'type', headerName: 'Type', width: 120 },
        { field: 'trigger', headerName: 'Trigger', width: 130 },
        {
            field: 'status',
            headerName: 'Status',
            width: 110,
            cellRenderer: (p: ICellRendererParams<JobRunRow>) => statusBadgeHtml(p.value as string),
        },
        { field: 'durationMs', headerName: 'Duration', width: 110, valueFormatter: (p) => fmtDuration(p.value as number) },
        { field: 'message', headerName: 'Message', flex: 1, minWidth: 220 },
    ];

    readonly runActions: InspectoRowAction<JobRunRow>[] = [
        { icon: 'heroicons_outline:information-circle', hint: 'Details', onClick: (r) => this.openRunDetail(r) },
    ];

    ngOnInit(): void {
        this.load();
    }

    ngOnDestroy(): void {
        this.liveSub?.unsubscribe();
    }

    setMode(m: JobsViewMode): void {
        if (this.mode === m) return;
        this.mode = m;
        if (m === 'reporting' && !this.reportLoaded && !this.reportLoading) this.loadReport();
    }

    // ── schedules ────────────────────────────────────────────────────────────────

    load(): void {
        this.loading = true;
        this.unavailable = false;
        this.api.list().subscribe({
            next: (j) => {
                this.jobs = j;
                this.loading = false;
            },
            error: (e) => {
                this.loading = false;
                this.jobs = [];
                this.unavailable = e?.status === 404;
            },
        });
    }

    async trigger(job: JobView): Promise<void> {
        if (!(await this.confirm.confirm(`Run job "${job.name}" now?`, 'Run job'))) return;
        this.api.trigger(job.name).subscribe({
            next: (r) => {
                this.toastr.success(`${job.name}: ${r.status}`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Run failed for ${job.name}`)),
        });
    }

    openRuns(job: JobView): void {
        this.dialog.open(JobRunsDialog, { data: { job: job.name }, width: '820px', maxHeight: '80vh' });
    }

    // ── reporting (T27) ──────────────────────────────────────────────────────────

    /** Success rate as a whole-number percentage for the metric card. */
    get successPct(): number {
        return this.metrics ? Math.round(this.metrics.successRate * 100) : 0;
    }

    /** Run all three reporting queries. `silent` (live-tail tick) keeps the view instead of flashing the loader. */
    loadReport(silent = false): void {
        if (!silent) this.reportLoading = true;
        const job = this.fJob.trim() || undefined;
        forkJoin({
            metrics: this.api.metrics(job),
            runs: this.api.recentRuns(this.fLimit, job),
            failures: this.api.failures(this.fDays),
        }).subscribe({
            next: ({ metrics, runs, failures }) => {
                this.metrics = metrics;
                this.runs = runs;
                this.chartData = this.toChartData(failures);
                this.reportDisabled = false;
                this.reportLoaded = true;
                this.reportLoading = false;
            },
            error: (e) => {
                this.reportLoading = false;
                if (!silent) {
                    this.reportDisabled = e?.status === 404;   // backend off vs a transient failure
                    this.metrics = null;
                    this.runs = [];
                    this.chartData = null;
                    this.reportLoaded = true;
                }
            },
        });
    }

    /** Failure trend → Chart.js bar data (oldest→newest); null when there is nothing to plot. */
    private toChartData(days: JobFailureDay[]): ChartData | null {
        if (!days.length) return null;
        const ordered = [...days].reverse();
        return {
            labels: ordered.map((d) => d.day),
            datasets: [
                { label: 'Total', data: ordered.map((d) => d.total), backgroundColor: CHART_SERIES.primary },
                { label: 'Failed', data: ordered.map((d) => d.failed), backgroundColor: CHART_SERIES.error },
            ],
        };
    }

    resetReportFilter(): void {
        this.fJob = '';
        this.fLimit = 100;
        this.fDays = 30;
        this.loadReport();
    }

    toggleLive(on: boolean): void {
        this.live = on;
        this.liveSub?.unsubscribe();
        this.liveSub = undefined;
        if (on) this.liveSub = visibleInterval(LIVE_TAIL_MS).subscribe(() => this.loadReport(true));
    }

    openRunDetail(row: JobRunRow): void {
        this.dialog.open(JobRunDetailDialog, { data: row, width: '600px', maxHeight: '85vh' });
    }
}
