import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RequirementKind } from 'app/inspecto/requirement';

export interface RequirementFormResult {
    title: string;
    kind: RequirementKind;
    description: string;
}

const KINDS: { value: RequirementKind; label: string }[] = [
    { value: 'kpi', label: 'KPI' },
    { value: 'report', label: 'Report' },
    { value: 'reconciliation', label: 'Reconciliation' },
    { value: 'rule', label: 'Decision rule' },
];

/** Submit a new Requirement — ask-the-minimum: title, kind, and a free-text description. No id is
 *  shown (nothing else references a requirement by id, so there's nothing to name). */
@Component({
    selector: 'app-requirement-form-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>New requirement</h2>
        <mat-dialog-content>
            <form [formGroup]="form" class="flex flex-col gap-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Title</mat-label>
                    <input matInput formControlName="title" placeholder="e.g. daily churn KPI by region" cdkFocusInitial />
                    @if (form.controls.title.hasError('required') && form.controls.title.touched) {
                        <mat-error>Title is required</mat-error>
                    }
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Kind</mat-label>
                    <mat-select formControlName="kind">
                        @for (k of kinds; track k.value) { <mat-option [value]="k.value">{{ k.label }}</mat-option> }
                    </mat-select>
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Description</mat-label>
                    <textarea matInput rows="4" formControlName="description" placeholder="What do you need, and why?"></textarea>
                    @if (form.controls.description.hasError('required') && form.controls.description.touched) {
                        <mat-error>A description is required</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="submit()">Submit</button>
        </mat-dialog-actions>
    `,
})
export class RequirementFormDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<RequirementFormDialog, RequirementFormResult>);

    readonly kinds = KINDS;
    readonly form = this.fb.group({
        title: ['', Validators.required],
        kind: this.fb.nonNullable.control<RequirementKind>('kpi'),
        description: ['', Validators.required],
    });

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.ref.close({ title: v.title.trim(), kind: v.kind, description: v.description.trim() });
    }
}
