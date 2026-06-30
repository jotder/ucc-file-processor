import { Component, inject, OnInit } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { forkJoin } from 'rxjs';
import { AuditRow, RunsService } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';

/** Batch detail — summary + member files + input→output lineage for one batch. */
@Component({
    selector: 'app-batch-detail-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatProgressSpinnerModule, DataTableComponent],
    template: `
        <h2 mat-dialog-title>Batch {{ data.batchId }}</h2>
        <mat-dialog-content>
            @if (loading) {
                <mat-progress-spinner diameter="24" mode="indeterminate"></mat-progress-spinner>
            } @else {
                <div class="font-semibold">Summary</div>
                @if (!batchRow) {
                    <p class="text-secondary text-sm">No batch-summary row found for this id.</p>
                } @else {
                    <table class="mt-1 text-sm">
                        <tbody>
                            @for (kv of batchSummary; track kv.key) {
                                <tr>
                                    <th scope="row" class="pr-4 text-left align-top">{{ kv.key }}</th>
                                    <td>{{ kv.value }}</td>
                                </tr>
                            }
                        </tbody>
                    </table>
                }

                <div class="mt-4 font-semibold">Member files ({{ batchFiles.length }})</div>
                <inspecto-data-table
                    tier="mini"
                    sourceName="files"
                    [rows]="batchFiles"
                    height="14rem"
                    noRowsTitle="No member files"
                />

                <div class="mt-4 font-semibold">Lineage ({{ batchLineage.length }})</div>
                <inspecto-data-table
                    tier="mini"
                    sourceName="lineage"
                    [rows]="batchLineage"
                    height="14rem"
                    noRowsTitle="No lineage"
                />
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class BatchDetailDialog implements OnInit {
    readonly data = inject<{ pipeline: string; batchId: string }>(MAT_DIALOG_DATA);
    private api = inject(RunsService);

    loading = true;
    batchRow: AuditRow | null = null;
    batchFiles: AuditRow[] = [];
    batchLineage: AuditRow[] = [];

    get batchSummary(): { key: string; value: string }[] {
        return this.batchRow ? Object.entries(this.batchRow).map(([key, value]) => ({ key, value })) : [];
    }

    ngOnInit(): void {
        const { pipeline, batchId } = this.data;
        forkJoin({
            batches: this.api.batches(pipeline),
            files: this.api.files(pipeline),
            lineage: this.api.lineage(pipeline, batchId),
        }).subscribe({
            next: ({ batches, files, lineage }) => {
                this.batchRow = batches.find((b) => b['batch_id'] === batchId) || null;
                this.batchFiles = files.filter((f) => f['batch_id'] === batchId);
                this.batchLineage = lineage;
                this.loading = false;
            },
            error: () => {
                this.loading = false;
            },
        });
    }
}
