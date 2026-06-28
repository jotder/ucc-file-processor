import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface ChartSaveData {
    suggestedId: string;
    /** True when editing — the id is fixed and the field is read-only. */
    lockId: boolean;
}

/** Save-as-chart dialog — names the chart and closes with the id (or undefined on cancel). */
@Component({
    selector: 'app-chart-save-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Save chart</h2>
        <mat-dialog-content>
            <form [formGroup]="form" (ngSubmit)="save()">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Chart id</mat-label>
                    <input matInput formControlName="name" [readonly]="data.lockId" placeholder="e.g. duration_by_tariff" />
                    @if (form.controls.name.hasError('pattern')) {
                        <mat-error>Letters, digits, dot, dash, underscore; start alphanumeric.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class ChartSaveDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<ChartSaveDialog, string>);
    readonly data = inject<ChartSaveData>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        name: [this.data.suggestedId, [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
    });

    save(): void {
        const ctrl = this.form.controls.name;
        const name = String(ctrl.value ?? '').trim();
        if (!name || ctrl.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close(name);
    }
}
