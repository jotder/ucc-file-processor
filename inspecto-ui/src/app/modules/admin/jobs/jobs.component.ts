import { Component, DestroyRef, inject, OnDestroy, OnInit, signal, ViewEncapsulation } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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
import { ActivatedRoute, Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    JobDetail,
    JobFailureDay,
    JobMetrics,
    JobRunRow,
    JobsService,
    JobView,
    LensService,
    visibleInterval,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { InspectoSplitDirective } from 'app/inspecto/components/split.directive';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { CHART_SERIES } from 'app/inspecto/theme/chart-tokens';
import { fmtDuration, scheduleSummary, whatScheduled } from './job-display';
import { JobDetailComponent } from './job-detail/job-detail.component';
import { JobFormDialog } from './job-form.dialog';
import { JobRunDetailDialog } from './job-run-detail.dialog';

/** Which lens the Jobs pane shows: the schedule registry, or execution reporting over the run history. */
export type JobsViewMode = 'schedules' | 'reporting';

/** Live-tail poll cadence (ms) for the reporting view — pauses while the tab is hidden. */
const LIVE_TAIL_MS = 5000;

// fmtDuration moved to job-display (shared with the detail view); re-exported for existing importers.
export { fmtDuration };

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
        InspectoSplitDirective,
        JobDetailComponent,
    ],
    templateUrl: './jobs.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class JobsComponent implements OnInit, OnDestroy {
    private api = inject(JobsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private router = inject(Router);
    private route = inject(ActivatedRoute);
    private destroyRef = inject(DestroyRef);
    /** Business lens = read-only across the Workbench (Wave-1 interview decision) — hides authoring. */
    protected lens = inject(LensService);

    mode: JobsViewMode = 'schedules';

    /** Job open in the side panel — driven by the `/jobs/:name` route param (R5). */
    readonly detailName = signal<string | null>(null);

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

    /** Open the create dialog; reload the list when a job is saved. */
    newJob(): void {
        this.dialog
            .open(JobFormDialog, { data: { existingNames: this.jobs.map((j) => j.name) }, width: '640px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r) => {
                if (r?.saved) {
                    this.toastr.success(`Job "${r.saved.name}" created`);
                    this.load();
                }
            });
    }

    readonly columnDefs: ColDef<JobView>[] = [
        { field: 'name', headerName: 'Job', flex: 1, minWidth: 160 },
        { headerName: "What's scheduled", flex: 1, minWidth: 150, valueGetter: (p) => (p.data ? whatScheduled(p.data) : '') },
        { headerName: 'Schedule', flex: 1, minWidth: 150, valueGetter: (p) => (p.data ? scheduleSummary(p.data) : '') },
        { field: 'nextFire', headerName: 'Next fire', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
        {
            field: 'enabled',
            headerName: 'Enabled',
            width: 110,
            cellRenderer: (p: ICellRendererParams<JobView>) => statusBadgeHtml(p.value ? 'enabled' : 'disabled'),
        },
        {
            field: 'lastStatus',
            headerName: 'Last result',
            width: 120,
            cellRenderer: (p: ICellRendererParams<JobView>) => (p.value ? statusBadgeHtml(p.value as string) : '—'),
        },
        { field: 'lastRunTime', headerName: 'Last run', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    /** Run/toggle are operational, not authoring — available in every lens. Reschedule/edit/delete open
     *  the config-authoring dialog or destroy the job, so they're hidden in the Business (read-only) lens. */
    get scheduleActions(): InspectoRowAction<JobView>[] {
        const ops: InspectoRowAction<JobView>[] = [
            { icon: 'heroicons_outline:play', hint: 'Run now', onClick: (j) => this.trigger(j) },
            {
                icon: (j) => (j.enabled ? 'heroicons_outline:pause-circle' : 'heroicons_outline:play-circle'),
                hint: (j) => (j.enabled ? 'Disable' : 'Enable'),
                onClick: (j) => this.toggleEnabled(j),
            },
        ];
        if (!this.lens.canAuthorWorkbench()) return ops;
        return [
            ...ops,
            { icon: 'heroicons_outline:calendar-days', hint: 'Reschedule', onClick: (j) => this.edit(j, true) },
            { icon: 'heroicons_outline:pencil-square', hint: 'Edit', onClick: (j) => this.edit(j, false) },
            { icon: 'heroicons_outline:trash', hint: 'Delete', onClick: (j) => this.remove(j) },
        ];
    }

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
        // Both `/jobs` and `/jobs/<name>` resolve to this component (see jobs.routes.ts) — the
        // param opens/closes the side panel while the list state survives.
        this.route.paramMap
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((pm) => this.detailName.set(pm.get('name')));
        // The command palette's "New job" handshake: `?create=1` opens the create dialog and is
        // stripped so Back / cancel-and-refresh doesn't retrigger it. The dialog must open only
        // after the strip navigation settles — MatDialog closes open dialogs on navigation.
        this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((q) => {
            if (q.get('create') != null) {
                void this.router
                    .navigate([], {
                        relativeTo: this.route,
                        queryParams: { create: null },
                        queryParamsHandling: 'merge',
                        replaceUrl: true,
                    })
                    .then(() => this.newJob());
            }
        });
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
            // v1 async contract: the trigger returns 202 + runId; the refreshed list shows the outcome.
            next: () => {
                this.toastr.success(`Job "${job.name}" run started.`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Run failed for ${job.name}`)),
        });
    }

    /** Row-click target → the job detail side panel (the `/jobs/:name` URL stays shareable). */
    openDetail(row: Record<string, unknown>): void {
        const name = row?.['name'] as string | undefined;
        if (name) this.router.navigate(['/jobs', name]);
    }

    closeDetail(): void {
        this.router.navigate(['/jobs']);
    }

    toggleEnabled(job: JobView): void {
        this.api.setEnabled(job.name, !job.enabled).subscribe({
            next: () => {
                this.toastr.success(`${job.name} ${job.enabled ? 'disabled' : 'enabled'}`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not update ${job.name}`)),
        });
    }

    /** Open the edit (or reschedule-focused) dialog seeded with the job's full config; reload on save. */
    edit(job: JobView, focusSchedule: boolean): void {
        this.api.get(job.name).subscribe({
            next: (detail: JobDetail) => {
                this.dialog
                    .open(JobFormDialog, { data: { job: detail, focusSchedule }, width: '640px', maxHeight: '88vh' })
                    .afterClosed()
                    .subscribe((r) => {
                        if (r?.saved) {
                            this.toastr.success(`Job "${r.saved.name}" saved`);
                            this.load();
                        }
                    });
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load ${job.name}`)),
        });
    }

    async remove(job: JobView): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete scheduled job "${job.name}"?`, { title: 'Delete job' }))) return;
        this.api.remove(job.name).subscribe({
            next: () => {
                this.toastr.success(`Job "${job.name}" deleted`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not delete ${job.name}`)),
        });
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
