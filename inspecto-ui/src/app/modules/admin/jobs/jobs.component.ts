import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, JobsService, JobView } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { AssistDialog } from 'app/inspecto/components/assist.dialog';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { actionsColumn, fmtDateTime, refreshActionsCells, INSPECTO_DEFAULT_COL_DEF, InspectoGridThemeService } from 'app/inspecto/grid';
import { JobRunsDialog } from './job-runs.dialog';

/**
 * Jobs & schedules — every registered cron / event / manual job with last-status, next-fire and
 * trigger-now; a runs dialog shows run history (ported from inspector-ui onto the gamma shell).
 * "New schedule" (nl-to-schedule assist flow) returns once the AssistPanel component is ported.
 */
@Component({
    selector: 'app-jobs',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        AgGridAngular,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './jobs.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class JobsComponent implements OnInit {
    private api = inject(JobsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    readonly themeSvc = inject(InspectoGridThemeService);

    jobs: JobView[] = [];
    loading = false;
    unavailable = false;
    quickFilter = '';

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

    readonly defaultColDef = INSPECTO_DEFAULT_COL_DEF;
    readonly columnDefs: ColDef<JobView>[] = [
        { field: 'name', headerName: 'Job', flex: 1 },
        { field: 'type', headerName: 'Type', width: 110 },
        { field: 'cron', headerName: 'Cron', flex: 1 },
        { field: 'onPipeline', headerName: 'On pipeline', flex: 1 },
        { field: 'enabled', headerName: 'Enabled', width: 100 },
        { field: 'lastStatus', headerName: 'Last status', width: 120 },
        { field: 'lastRunTime', headerName: 'Last run', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'nextFire', headerName: 'Next fire', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
        actionsColumn<JobView>([
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
        ], 120),
    ];

    ngOnInit(): void {
        this.load();
    }

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

    readonly refreshActions = refreshActionsCells;
}
