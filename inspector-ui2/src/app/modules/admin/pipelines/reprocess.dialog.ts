import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

/** Reprocess-batch prompt — returns the entered batch id, or undefined on cancel. */
@Component({
    selector: 'app-reprocess-dialog',
    standalone: true,
    imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
    template: `
        <h2 mat-dialog-title>Reprocess batch</h2>
        <mat-dialog-content>
            <p class="mb-3">Reprocess a committed batch of <b>{{ data.pipeline }}</b>.</p>
            <mat-form-field class="w-full">
                <mat-label>Batch id</mat-label>
                <input matInput [(ngModel)]="batchId" placeholder="batch id" />
            </mat-form-field>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Cancel</button>
            <button mat-flat-button color="primary" [disabled]="!batchId.trim()" [mat-dialog-close]="batchId">
                Reprocess
            </button>
        </mat-dialog-actions>
    `,
})
export class ReprocessDialog {
    readonly data = inject<{ pipeline: string }>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<ReprocessDialog>);
    batchId = '';
}
