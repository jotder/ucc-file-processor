import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, JobDetail, JobsService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { fmtDateTime } from 'app/inspecto/grid';
import { Dashboard } from '../studio/dashboards/dashboard-types';
import { DashboardsService } from '../studio/dashboards/dashboards.service';
import { ScheduleExportData, ScheduleExportDialog, ScheduleExportResult } from './schedule-export.dialog';

/** A `type:'report'` job's dashboard-export params (C6) — no new entity, params carry the shape. */
interface ReportJobParams {
    dashboardId: string;
    format: 'csv' | 'pdf' | 'png';
    recipients: string[];
}

/**
 * KPI & Reports — the read-only landing gallery over the Studio dashboards, extended (C6) with
 * **scheduled exports**: a dashboard export IS a Job (`type: 'report'`, reusing the existing
 * scheduler/run-history/live-tail wholesale) so this pane only adds the "Schedule export" action and
 * a status list — authoring stays here, execution stays in Jobs.
 */
@Component({
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        RouterLink,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: `./kpi-reports.component.html`,
})
export class KpiReportsComponent implements OnInit {
    private dashboardsApi = inject(DashboardsService);
    private jobsApi = inject(JobsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    private router = inject(Router);
    protected lens = inject(LensService);

    readonly dashboards = signal<Dashboard[]>([]);
    readonly reportJobs = signal<JobDetail[]>([]);
    readonly loading = signal(true);
    /** True when the dashboards fetch itself failed — distinguishes a load error from a genuinely
     *  empty gallery so the template can offer Retry instead of the "create one" empty state. */
    readonly loadError = signal(false);
    readonly statusBadgeHtml = statusBadgeHtml;
    readonly fmtDateTime = fmtDateTime;

    createDashboard(): void {
        this.router.navigate(['/studio/dashboards/new']);
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.loadError.set(false);
        this.dashboardsApi.list().subscribe({
            next: (d) => {
                this.dashboards.set(d);
                this.loading.set(false);
            },
            error: () => {
                this.loadError.set(true);
                this.loading.set(false);
            },
        });
        this.loadReportDetails();
    }

    /** The list projection omits `params` (dashboardId/format/recipients) — fetch full detail per job. */
    private loadReportDetails(): void {
        this.jobsApi.list().subscribe((jobs) => {
            const names = jobs.filter((j) => j.type === 'report').map((j) => j.name);
            if (!names.length) {
                this.reportJobs.set([]);
                return;
            }
            Promise.all(names.map((n) => this.jobsApi.get(n).toPromise())).then((details) => {
                this.reportJobs.set(details.filter((d): d is JobDetail => !!d));
            });
        });
    }

    jobsFor(dashboardId: string): JobDetail[] {
        return this.reportJobs().filter((j) => this.paramsOf(j).dashboardId === dashboardId);
    }

    paramsOf(j: JobDetail): ReportJobParams {
        const p = j.params ?? {};
        return {
            dashboardId: String(p['dashboardId'] ?? ''),
            format: (p['format'] as ReportJobParams['format']) ?? 'csv',
            recipients: (p['recipients'] as string[]) ?? [],
        };
    }

    scheduleExport(d: Dashboard): void {
        const data: ScheduleExportData = {
            dashboardId: d.id,
            dashboardName: d.name,
            existingNames: this.reportJobs().map((j) => j.name),
        };
        this.dialog
            .open(ScheduleExportDialog, { data, width: '560px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: ScheduleExportResult) => {
                if (r?.saved) {
                    this.toastr.success(`Scheduled export "${r.saved.name}" created`);
                    this.loadReportDetails();
                }
            });
    }

    editSchedule(job: JobDetail, d: Dashboard): void {
        const data: ScheduleExportData = { dashboardId: d.id, dashboardName: d.name, job };
        this.dialog
            .open(ScheduleExportDialog, { data, width: '560px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: ScheduleExportResult) => {
                if (r?.saved) {
                    this.toastr.success(`Scheduled export "${r.saved.name}" saved`);
                    this.loadReportDetails();
                }
            });
    }

    /** Run now: triggers the underlying job — a successful run produces a downloadable artifact. */
    runNow(job: JobDetail): void {
        this.jobsApi.trigger(job.name).subscribe({
            next: () => {
                this.toastr.success(`Export "${job.name}" ran.`);
                this.loadReportDetails();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not run "${job.name}".`)),
        });
    }

    /** Download the latest run's artifact (mirrors the events CSV-export client pattern). */
    downloadLatest(job: JobDetail): void {
        if (!job.lastRunTime) {
            this.toastr.warning(`"${job.name}" has not run yet.`);
            return;
        }
        this.jobsApi.runs(job.name).subscribe({
            next: (runs) => {
                const latest = runs[0];
                if (!latest) return;
                this.jobsApi.runArtifact(job.name, latest.runId).subscribe({
                    next: (a) => {
                        const blob = new Blob([a.content], { type: a.mime });
                        const url = URL.createObjectURL(blob);
                        const link = document.createElement('a');
                        link.href = url;
                        link.download = a.filename;
                        link.click();
                        URL.revokeObjectURL(url);
                    },
                    error: (e) => this.toastr.error(apiErrorMessage(e, 'No artifact for the latest run.')),
                });
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not load run history.')),
        });
    }

    async removeSchedule(job: JobDetail): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete scheduled export "${job.name}"?`))) return;
        this.jobsApi.remove(job.name).subscribe({
            next: () => {
                this.toastr.success(`Scheduled export "${job.name}" deleted`);
                this.reportJobs.set(this.reportJobs().filter((j) => j.name !== job.name));
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not delete "${job.name}".`)),
        });
    }
}
