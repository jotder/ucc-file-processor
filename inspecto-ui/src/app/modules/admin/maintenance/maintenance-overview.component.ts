import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import {
  HealthDetails,
  HealthService,
  JobRunRow,
  JobView,
  JobsService,
  RunArtifactRow,
  apiErrorMessage,
} from 'app/inspecto/api';
import { StatusBadgeComponent, statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

/** One storage axis row derived from the latest storage_report Run Artifacts (MNT-3 series). */
interface StorageAxis {
  axis: string;
  bytes: number;
  ref: string | null;
}

/**
 * System Maintenance → Overview (MNT-11, plan §5): the platform-maintaining-itself surface —
 * per-subsystem health (/health/details, MNT-15), the maintenance job fleet with last/next runs
 * (backup status included), recent failed maintenance runs (the DuckDB run projection, degrades
 * to a note when `-Djobs.backend` is off), and storage usage from the latest `storage_report`
 * Run Artifacts. Observation-and-action surface only — authoring stays in Workbench → Jobs.
 */
@Component({
  selector: 'inspecto-maintenance-overview',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, StatusBadgeComponent, InspectoSkeletonComponent,
    InspectoEmptyStateComponent, DataTableComponent],
  templateUrl: './maintenance-overview.component.html',
})
export class MaintenanceOverviewComponent implements OnInit {
  private healthApi = inject(HealthService);
  private jobsApi = inject(JobsService);

  loading = signal(true);
  health = signal<HealthDetails | null>(null);
  healthError = signal<string | null>(null);
  maintenanceJobs = signal<JobView[]>([]);
  jobsError = signal<string | null>(null);
  failedRuns = signal<JobRunRow[]>([]);
  /** Null = projection answered; a note when `/jobs/runs` 404s (`-Djobs.backend` off). */
  runsNote = signal<string | null>(null);
  storageAxes = signal<StorageAxis[]>([]);
  lastBackup = signal<RunArtifactRow | null>(null);

  readonly jobColumns: ColDef<JobView>[] = [
    { field: 'name', headerName: 'Job', flex: 1 },
    { field: 'cron', headerName: 'Cron', width: 130 },
    {
      field: 'lastStatus',
      headerName: 'Last status',
      width: 140,
      cellRenderer: (p: ICellRendererParams<JobView>) => statusBadgeHtml((p.value as string) || '—'),
    },
    { field: 'lastRunTime', headerName: 'Last run', width: 170, valueFormatter: fmtDateTime },
    { field: 'nextFire', headerName: 'Next fire', width: 170, valueFormatter: fmtDateTime },
  ];

  readonly failedRunColumns: ColDef<JobRunRow>[] = [
    { field: 'job', headerName: 'Job', flex: 1 },
    {
      field: 'status',
      headerName: 'Status',
      width: 130,
      cellRenderer: (p: ICellRendererParams<JobRunRow>) => statusBadgeHtml(p.value as string),
    },
    { field: 'startTime', headerName: 'Started', width: 170, valueFormatter: fmtDateTime },
    { field: 'message', headerName: 'Message', flex: 2 },
  ];

  ngOnInit(): void {
    this.refresh();
  }

  /** Independent fetches — one failing subsystem never blanks the page (§8). */
  refresh(): void {
    this.loading.set(true);
    this.healthApi.details().subscribe({
      next: (h) => this.health.set(h),
      error: (err) => this.healthError.set(apiErrorMessage(err, 'Health details unavailable')),
    });
    this.jobsApi
      .list()
      .pipe(catchError(() => of([] as JobView[])))
      .subscribe((jobs) => {
        const maint = jobs.filter((j) => j.type === 'maintenance');
        this.maintenanceJobs.set(maint);
        this.jobsError.set(jobs.length === 0 ? 'No jobs registered in this space.' : null);
        this.loading.set(false);
        this.loadArtifacts(maint.map((j) => j.name));
      });
    this.jobsApi.recentRuns(100).subscribe({
      next: (rows) => {
        this.failedRuns.set(
          rows.filter((r) => r.type === 'maintenance' && (r.status === 'FAILED' || r.status === 'REJECTED')),
        );
        this.runsNote.set(null);
      },
      error: () =>
        this.runsNote.set('Run projection not enabled (-Djobs.backend) — per-job history is still on each job.'),
    });
  }

  /** Latest Run Artifacts across the maintenance fleet → storage axes (axis:*) + the last backup. */
  private loadArtifacts(jobNames: string[]): void {
    if (jobNames.length === 0) {
      this.storageAxes.set([]);
      this.lastBackup.set(null);
      return;
    }
    forkJoin(
      jobNames.map((n) => this.jobsApi.latestArtifacts(n).pipe(catchError(() => of([] as RunArtifactRow[])))),
    )
      .pipe(map((lists) => lists.flat()))
      .subscribe((artifacts) => {
        this.storageAxes.set(
          artifacts
            .filter((a) => a.name.startsWith('axis:'))
            .map((a) => ({ axis: a.name.substring('axis:'.length), bytes: a.bytes, ref: a.ref }))
            .sort((a, b) => b.bytes - a.bytes),
        );
        const backups = artifacts.filter((a) => a.name === 'backup');
        this.lastBackup.set(backups.length ? backups[backups.length - 1] : null);
      });
  }

  subsystems(): { key: string; status: string; detail: string }[] {
    const h = this.health();
    if (!h) return [];
    return Object.entries(h.subsystems).map(([key, s]) => ({ key, status: s.status, detail: s.detail }));
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let v = bytes;
    let u = -1;
    do {
      v /= 1024;
      u++;
    } while (v >= 1024 && u < units.length - 1);
    return `${v.toFixed(1)} ${units[u]}`;
  }
}
