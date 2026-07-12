import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { currentOperator, displayStatus } from './mail-model';

/**
 * Add a member Incident to a Case's Contents (C1) — picks a not-yet-contained incident and creates
 * the {@code CONTAINS} edge. Closes with `true` when a member was added.
 */
@Component({
    selector: 'app-add-member-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatSelectModule],
    template: `
        <h2 mat-dialog-title>Add member incident</h2>
        <mat-dialog-content class="pt-2">
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Incident</mat-label>
                <mat-select [formControl]="pick" required>
                    @for (i of candidates(); track i.id) {
                        <mat-option [value]="i.id">[{{ displayStatus(i) }}] {{ i.title }} ({{ i.id }})</mat-option>
                    }
                </mat-select>
                @if (pick.hasError('required') && pick.touched) {
                    <mat-error>Pick an incident.</mat-error>
                }
            </mat-form-field>
            @if (!candidates().length) {
                <p class="text-secondary text-sm">Every incident is already contained by this case (or none exist).</p>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="false">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving() || !candidates().length" (click)="add()">
                Add
            </button>
        </mat-dialog-actions>
    `,
})
export class AddMemberDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<AddMemberDialog>);
    private toastr = inject(ToastrService);
    readonly data = inject<{ caseId: string; exclude: string[] }>(MAT_DIALOG_DATA);

    readonly displayStatus = displayStatus;
    readonly pick = new FormControl<string | null>(null, Validators.required);
    readonly candidates = signal<OperationalObject[]>([]);
    readonly saving = signal(false);

    constructor() {
        const excluded = new Set(this.data.exclude);
        this.api.list({ type: 'INCIDENT', limit: 500 }).subscribe({
            next: (all) => this.candidates.set(all.filter((o) => !excluded.has(o.id))),
            error: () => this.candidates.set([]),
        });
    }

    add(): void {
        if (this.pick.invalid) {
            this.pick.markAsTouched();
            return;
        }
        this.saving.set(true);
        this.api.link(this.data.caseId, this.pick.value!, 'CONTAINS', currentOperator()).subscribe({
            next: () => {
                this.toastr.success('Member added');
                this.ref.close(true);
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Add failed'));
            },
        });
    }
}
