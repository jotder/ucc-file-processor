import { Component, inject, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { InboxStatus, PipelinesService, SourceView } from 'app/inspecto/api';

/**
 * Source detail dialog — the full `/sources` config for one source, a link to its bound connection
 * profile, the current DB watermark slice and the live inbox status (pending count + running) from
 * GET /pipelines/{name}/pending.
 */
@Component({
    selector: 'app-source-detail-dialog',
    standalone: true,
    imports: [RouterLink, MatDialogModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
    template: `
        <h2 mat-dialog-title>
            <span class="font-mono">{{ data.id }}</span>
            <span class="text-secondary text-base font-normal"> · {{ data.pipeline }}</span>
        </h2>
        <mat-dialog-content class="space-y-4">
            <!-- Live inbox status -->
            <div class="bg-card flex items-center gap-4 rounded-xl p-4 shadow-sm">
                @if (statusLoading) {
                    <mat-progress-spinner diameter="20" mode="indeterminate"></mat-progress-spinner>
                    <span class="text-secondary">Loading inbox status…</span>
                } @else if (status) {
                    <div class="flex items-center gap-2">
                        <mat-icon
                            class="icon-size-5"
                            [svgIcon]="status.running ? 'heroicons_outline:bolt' : 'heroicons_outline:pause'"
                        ></mat-icon>
                        <span class="font-medium">{{ status.running ? 'Processing' : 'Idle' }}</span>
                    </div>
                    <div class="text-secondary">
                        Pending: <span class="font-medium">{{ status.pending < 0 ? '—' : status.pending }}</span>
                    </div>
                } @else {
                    <span class="text-secondary">Inbox status unavailable.</span>
                }
            </div>

            <!-- Config grid -->
            <div class="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                <div><span class="text-secondary font-medium">connector:</span> {{ data.connector }}</div>
                <div>
                    <span class="text-secondary font-medium">connection:</span>
                    @if (data.connection) {
                        <a
                            class="text-primary hover:underline"
                            [routerLink]="['/connections']"
                            (click)="close()"
                            >{{ data.connection }}</a
                        >
                    } @else {
                        <span class="text-secondary">— (inline / local)</span>
                    }
                </div>
                <div><span class="text-secondary font-medium">dedup mode:</span> {{ data.duplicateMode }}</div>
                <div><span class="text-secondary font-medium">on change:</span> {{ data.duplicateOnChange }}</div>
                <div><span class="text-secondary font-medium">guarantee:</span> {{ data.guarantee }}</div>
                <div><span class="text-secondary font-medium">recursive depth:</span> {{ data.recursiveDepth }}</div>
                <div>
                    <span class="text-secondary font-medium">watermark:</span>
                    {{ data.incrementalWatermark ?? '— (full listing)' }}
                </div>
                <div>
                    <span class="text-secondary font-medium">db watermark:</span>
                    {{ data.dbWatermarkCurrent ?? '—' }}
                </div>
                <div><span class="text-secondary font-medium">fetch parallel:</span> {{ data.fetchParallel }}</div>
                <div><span class="text-secondary font-medium">rate limit:</span> {{ data.fetchRateLimit }}</div>
                <div><span class="text-secondary font-medium">post action:</span> {{ data.postAction }}</div>
            </div>

            <!-- Includes / excludes -->
            <div class="space-y-2 text-sm">
                <div>
                    <span class="text-secondary font-medium">includes:</span>
                    @if (data.includes.length) {
                        @for (g of data.includes; track g) {
                            <span class="mr-1 inline-block rounded bg-gray-200 px-2 py-0.5 font-mono text-xs dark:bg-gray-700">{{ g }}</span>
                        }
                    } @else {
                        <span class="text-secondary">— (all)</span>
                    }
                </div>
                <div>
                    <span class="text-secondary font-medium">excludes:</span>
                    @if (data.excludes.length) {
                        @for (g of data.excludes; track g) {
                            <span class="mr-1 inline-block rounded bg-gray-200 px-2 py-0.5 font-mono text-xs dark:bg-gray-700">{{ g }}</span>
                        }
                    } @else {
                        <span class="text-secondary">—</span>
                    }
                </div>
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class SourceDetailDialog implements OnInit {
    readonly data = inject<SourceView>(MAT_DIALOG_DATA);
    private pipelines = inject(PipelinesService);
    private ref = inject(MatDialogRef<SourceDetailDialog>);

    status: InboxStatus | null = null;
    statusLoading = true;

    ngOnInit(): void {
        this.pipelines.pending(this.data.pipeline).subscribe({
            next: (s) => {
                this.status = s;
                this.statusLoading = false;
            },
            error: () => {
                this.statusLoading = false;
            },
        });
    }

    close(): void {
        this.ref.close();
    }
}
