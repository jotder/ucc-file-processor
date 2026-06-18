import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { JobRunRow } from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { fmtDuration } from './jobs.component';

/** Single-run detail — every field of one durable {@link JobRunRow} from the reporting projection (T27). */
@Component({
    selector: 'app-job-run-detail-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, StatusBadgeComponent],
    template: `
        <h2 mat-dialog-title class="flex items-center gap-3">
            <inspecto-status-badge [value]="data.status" />
            <span class="font-mono text-base">{{ data.job }}</span>
        </h2>
        <mat-dialog-content class="space-y-4">
            <div class="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                <div><span class="text-secondary font-medium">run id:</span> <span class="font-mono text-xs">{{ data.runId }}</span></div>
                <div><span class="text-secondary font-medium">type:</span> {{ data.type }}</div>
                <div><span class="text-secondary font-medium">trigger:</span> {{ data.trigger }}</div>
                <div><span class="text-secondary font-medium">duration:</span> {{ fmt(data.durationMs) }}</div>
                <div><span class="text-secondary font-medium">started:</span> <span class="font-mono text-xs">{{ data.startTime || '—' }}</span></div>
                <div><span class="text-secondary font-medium">ended:</span> <span class="font-mono text-xs">{{ data.endTime || '—' }}</span></div>
            </div>
            <div>
                <div class="text-secondary mb-1 text-sm font-medium">message</div>
                <div class="font-mono text-xs break-all">{{ data.message || '— (none)' }}</div>
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class JobRunDetailDialog {
    readonly data = inject<JobRunRow>(MAT_DIALOG_DATA);
    fmt = fmtDuration;
}
