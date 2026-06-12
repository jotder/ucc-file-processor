import { Component, inject, OnInit } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef } from 'ag-grid-community';
import { JobRun, JobsService } from 'app/inspecto/api';
import { fmtDateTime, INSPECTO_DEFAULT_COL_DEF, InspectoGridThemeService } from 'app/inspecto/grid';

/** Run-history dialog for one job. */
@Component({
    selector: 'app-job-runs-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatProgressSpinnerModule, AgGridAngular],
    template: `
        <h2 mat-dialog-title>Run history — {{ data.job }}</h2>
        <mat-dialog-content>
            @if (loading) {
                <mat-progress-spinner diameter="24" mode="indeterminate"></mat-progress-spinner>
            } @else {
                <ag-grid-angular
                    class="h-96 w-full"
                    [theme]="themeSvc.theme()"
                    [rowData]="runs"
                    [columnDefs]="columnDefs"
                    [defaultColDef]="defaultColDef"
                ></ag-grid-angular>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class JobRunsDialog implements OnInit {
    readonly data = inject<{ job: string }>(MAT_DIALOG_DATA);
    private api = inject(JobsService);
    readonly themeSvc = inject(InspectoGridThemeService);

    runs: JobRun[] = [];
    loading = true;

    readonly defaultColDef = INSPECTO_DEFAULT_COL_DEF;
    readonly columnDefs: ColDef<JobRun>[] = [
        { field: 'runId', headerName: 'Run', flex: 1 },
        { field: 'status', headerName: 'Status', width: 100 },
        { field: 'triggerType', headerName: 'Trigger', width: 100 },
        { field: 'startTime', headerName: 'Started', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'endTime', headerName: 'Ended', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
        { field: 'durationMs', headerName: 'ms', width: 90 },
        { field: 'error', headerName: 'Error', flex: 1 },
    ];

    ngOnInit(): void {
        this.api.runs(this.data.job).subscribe({
            next: (r) => {
                this.runs = r;
                this.loading = false;
            },
            error: () => {
                this.loading = false;
            },
        });
    }
}
