import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { DxDataGridModule } from 'devextreme-angular/ui/data-grid';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import { DxToolbarModule } from 'devextreme-angular/ui/toolbar';
import { DxPopupModule } from 'devextreme-angular/ui/popup';
import { DxLoadIndicatorModule } from 'devextreme-angular/ui/load-indicator';
import notify from 'devextreme/ui/notify';
import { confirm } from 'devextreme/ui/dialog';
import { JobsService, JobView, JobRun } from '../../shared/api';
import { AssistPanelComponent } from '../../shared/components';
import { AuthService } from '../../shared/services';

/**
 * Jobs & schedules — every registered cron / event / manual job with last-status, next-fire and
 * trigger-now. A "runs" button opens the run history; "New schedule" launches the nl-to-schedule
 * assist flow (natural language → draft .toon + nextRuns + findings, draft-only).
 */
@Component({
  standalone: true,
  imports: [
    CommonModule, DxDataGridModule, DxButtonModule, DxToolbarModule,
    DxPopupModule, DxLoadIndicatorModule, AssistPanelComponent,
  ],
  templateUrl: './jobs.component.html',
})
export class JobsComponent implements OnInit {
  private api = inject(JobsService);
  private auth = inject(AuthService);

  jobs: JobView[] = [];
  loading = false;
  unavailable = false;

  // runs popup
  runsVisible = false;
  runsJob = '';
  runs: JobRun[] = [];
  runsLoading = false;

  // new-schedule assist popup (nl-to-schedule via the reusable AssistPanel)
  scheduleVisible = false;

  get canControl(): boolean { return this.auth.hasControl(); }
  get canAssist(): boolean { return this.auth.hasAssist(); }

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading = true;
    this.unavailable = false;
    this.api.list().subscribe({
      next: (j) => { this.jobs = j; this.loading = false; },
      error: (e) => { this.loading = false; this.jobs = []; this.unavailable = e?.status === 404; },
    });
  }

  trigger = async (e: { row: { data: JobView } }) => {
    const name = e.row.data.name;
    if (!(await confirm(`Run job <b>${name}</b> now?`, 'Run job'))) return;
    this.api.trigger(name).subscribe({
      next: (r) => { notify(`${name}: ${r.status}`, 'success', 2500); this.load(); },
    });
  };

  openRuns = (e: { row: { data: JobView } }) => {
    this.runsJob = e.row.data.name;
    this.runs = [];
    this.runsLoading = true;
    this.runsVisible = true;
    this.api.runs(this.runsJob).subscribe({
      next: (r) => { this.runs = r; this.runsLoading = false; },
      error: () => { this.runsLoading = false; },
    });
  };

  openSchedule(): void {
    this.scheduleVisible = true;
  }
}
