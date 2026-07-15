import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { Subscription } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    JobDetail,
    JobLogLine,
    JobRun,
    JobRunLogs,
    JobsService,
    visibleInterval,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent, statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';
import { fmtDuration, scheduleSummary, whatScheduled } from '../job-display';
import { JobFormDialog } from '../job-form.dialog';

const LIVE_TAIL_MS = 5000;

/**
 * Scheduler — single-job detail. Breadcrumb + header actions (run-now / reschedule / edit / enable-disable /
 * delete) over three tabs: **Schedule** (config overview), **Execution history** (the run table), and
 * **Logs & events** (the selected run's logs, live-tailed while it is RUNNING). Composes the shared
 * data-table + design-system pieces; all writes are mock-served (see the plan).
 *
 * Hosted two ways (ui-design-review R5): standalone full page (route-snapshot `name`, breadcrumb
 * header) or embedded as the Scheduler side panel (`[name]` + `[embedded]` inputs, compact header
 * with an X that emits `(closed)`).
 */
@Component({
    selector: 'app-job-detail',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTabsModule,
        MatTooltipModule,
        RouterLink,
        DataTableComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './job-detail.component.html',
})
export class JobDetailComponent implements OnInit, OnDestroy {
    private api = inject(JobsService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);

    /** Job name when embedded as a side panel; the route-snapshot param is the full-page fallback. */
    readonly nameInput = input<string | undefined>(undefined, { alias: 'name' });
    /** Embedded (side-panel) mode — compact header with a close button instead of breadcrumb chrome. */
    readonly embedded = input(false);
    readonly closed = output<void>();

    name = '';
    readonly job = signal<JobDetail | null>(null);
    readonly loading = signal(true);
    readonly runs = signal<JobRun[]>([]);
    readonly runsLoading = signal(false);
    readonly selectedRunId = signal<string | null>(null);
    readonly logs = signal<JobRunLogs | null>(null);
    readonly logsLoading = signal(false);
    selectedIndex = 0;

    readonly whatScheduled = computed(() => (this.job() ? whatScheduled(this.job()!) : ''));
    readonly scheduleSummary = computed(() => (this.job() ? scheduleSummary(this.job()!) : ''));
    readonly paramRows = computed(() => Object.entries(this.job()?.params ?? {}).map(([key, value]) => ({ key, value: String(value) })));
    readonly selectedRun = computed(() => this.runs().find((r) => r.runId === this.selectedRunId()) ?? null);

    readonly fmtDateTime = fmtDateTime;
    readonly fmtDuration = fmtDuration;

    readonly runColumns: ColDef<JobRun>[] = [
        { field: 'startTime', headerName: 'Started', width: 180, valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'status', headerName: 'Status', width: 110, cellRenderer: (p: ICellRendererParams<JobRun>) => statusBadgeHtml(p.value as string) },
        { field: 'triggerType', headerName: 'Trigger', width: 110 },
        { field: 'durationMs', headerName: 'Duration', width: 110, valueFormatter: (p) => fmtDuration(p.value as number) },
        { field: 'error', headerName: 'Message', flex: 1, minWidth: 240, valueFormatter: (p) => p.value ?? '—' },
    ];

    readonly logColumns: ColDef<JobLogLine>[] = [
        { field: 'ts', headerName: 'Time', width: 180, valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'level', headerName: 'Level', width: 90, cellRenderer: (p: ICellRendererParams<JobLogLine>) => statusBadgeHtml(p.value as string) },
        { field: 'message', headerName: 'Message', flex: 1, minWidth: 280 },
    ];

    private liveSub?: Subscription;

    /** The panel stays mounted while the user clicks through jobs — reload when the bound name changes. */
    private readonly reloadOnName = effect(() => {
        const n = this.nameInput();
        if (n === undefined || n === this.name) return;
        this.name = n;
        this.liveSub?.unsubscribe();
        this.liveSub = undefined;
        this.selectedRunId.set(null);
        this.logs.set(null);
        this.selectedIndex = 0;
        this.load();
    });

    ngOnInit(): void {
        this.name = this.nameInput() ?? this.route.snapshot.paramMap.get('name') ?? '';
        this.load();
    }

    ngOnDestroy(): void {
        this.liveSub?.unsubscribe();
    }

    load(): void {
        this.loading.set(true);
        this.api.get(this.name).subscribe({
            next: (j) => {
                this.job.set(j);
                this.loading.set(false);
            },
            error: () => {
                this.job.set(null);
                this.loading.set(false);
            },
        });
        this.loadRuns();
    }

    loadRuns(): void {
        this.runsLoading.set(true);
        this.api.runs(this.name).subscribe({
            next: (rs) => {
                this.runs.set(rs);
                this.runsLoading.set(false);
                if (rs.length && !this.selectedRunId()) this.selectRun(rs[0].runId);
            },
            error: () => {
                this.runs.set([]);
                this.runsLoading.set(false);
            },
        });
    }

    /** Select a run for the Logs tab; live-tail its logs while it is RUNNING. */
    selectRun(runId: string): void {
        this.selectedRunId.set(runId);
        this.loadLogs();
        this.liveSub?.unsubscribe();
        this.liveSub = undefined;
        if (this.selectedRun()?.status === 'RUNNING') {
            this.liveSub = visibleInterval(LIVE_TAIL_MS).subscribe(() => this.loadLogs(true));
        }
    }

    loadLogs(silent = false): void {
        const runId = this.selectedRunId();
        if (!runId) return;
        if (!silent) this.logsLoading.set(true);
        this.api.runLogs(this.name, runId).subscribe({
            next: (l) => {
                this.logs.set(l);
                this.logsLoading.set(false);
            },
            error: () => {
                this.logs.set(null);
                this.logsLoading.set(false);
            },
        });
    }

    /** History row click → select the run and jump to the Logs tab. */
    onRunRowClick(row: Record<string, unknown>): void {
        const runId = row?.['runId'] as string | undefined;
        if (runId) {
            this.selectRun(runId);
            this.selectedIndex = 2;
        }
    }

    // ── actions ──────────────────────────────────────────────────────────────────
    async runNow(): Promise<void> {
        const j = this.job();
        if (!j || !(await this.confirm.confirm(`Run job "${j.name}" now?`, 'Run job'))) return;
        this.api.trigger(j.name).subscribe({
            // v1 async contract: the trigger returns 202 + runId; the reloaded run list shows the outcome.
            next: () => {
                this.toastr.success(`Job "${j.name}" run started.`);
                this.selectedRunId.set(null);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Run failed for ${j.name}`)),
        });
    }

    toggleEnabled(): void {
        const j = this.job();
        if (!j) return;
        this.api.setEnabled(j.name, !j.enabled).subscribe({
            next: (updated) => {
                this.job.set(updated);
                this.toastr.success(`${j.name} ${j.enabled ? 'disabled' : 'enabled'}`);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not update ${j.name}`)),
        });
    }

    edit(focusSchedule: boolean): void {
        const j = this.job();
        if (!j) return;
        this.dialog
            .open(JobFormDialog, { data: { job: j, focusSchedule }, width: '640px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r) => {
                if (r?.saved) {
                    this.toastr.success(`Job "${r.saved.name}" saved`);
                    this.load();
                }
            });
    }

    async remove(): Promise<void> {
        const j = this.job();
        if (!j || !(await this.confirm.confirmDestructive(`Delete scheduled job "${j.name}"?`, { title: 'Delete job' }))) return;
        this.api.remove(j.name).subscribe({
            next: () => {
                this.toastr.success(`Job "${j.name}" deleted`);
                this.router.navigate(['/jobs']);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not delete ${j.name}`)),
        });
    }
}
