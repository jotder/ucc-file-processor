import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { INCIDENT_TAXONOMY, joinCategory, splitCategory } from './incident-taxonomy';

/**
 * 3-layer categorization picker (cascading L1 → L2 → L3 selects over {@link INCIDENT_TAXONOMY}).
 * Used by Accept (Identified → Diagnosing requires a category) and available for re-categorizing.
 * Closes with the joined category path, or null on cancel.
 */
@Component({
    selector: 'app-categorize-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatSelectModule],
    template: `
        <h2 mat-dialog-title>Categorize incident</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2">
            @if (data.hint) {
                <p class="text-secondary text-sm">{{ data.hint }}</p>
            }
            <form [formGroup]="form" class="flex flex-col gap-3">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Category</mat-label>
                    <mat-select formControlName="l1" required (selectionChange)="onL1()" cdkFocusInitial>
                        @for (l1 of l1Options; track l1) {
                            <mat-option [value]="l1">{{ l1 }}</mat-option>
                        }
                    </mat-select>
                    @if (form.controls.l1.hasError('required') && form.controls.l1.touched) {
                        <mat-error>Category is required.</mat-error>
                    }
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Subcategory</mat-label>
                    <mat-select formControlName="l2" required (selectionChange)="onL2()">
                        @for (l2 of l2Options(); track l2) {
                            <mat-option [value]="l2">{{ l2 }}</mat-option>
                        }
                    </mat-select>
                    @if (form.controls.l2.hasError('required') && form.controls.l2.touched) {
                        <mat-error>Subcategory is required.</mat-error>
                    }
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Detail</mat-label>
                    <mat-select formControlName="l3" required>
                        @for (l3 of l3Options(); track l3) {
                            <mat-option [value]="l3">{{ l3 }}</mat-option>
                        }
                    </mat-select>
                    @if (form.controls.l3.hasError('required') && form.controls.l3.touched) {
                        <mat-error>Detail is required.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="null">Cancel</button>
            <button mat-flat-button color="primary" (click)="apply()">Apply</button>
        </mat-dialog-actions>
    `,
})
export class CategorizeDialog {
    private ref = inject(MatDialogRef<CategorizeDialog>);
    private fb = inject(FormBuilder);
    readonly data = inject<{ current?: string; hint?: string }>(MAT_DIALOG_DATA);

    readonly l1Options = Object.keys(INCIDENT_TAXONOMY);

    readonly form = this.fb.group({
        l1: ['', Validators.required],
        l2: ['', Validators.required],
        l3: ['', Validators.required],
    });

    constructor() {
        const [l1, l2, l3] = splitCategory(this.data.current ?? '');
        if (l1 && INCIDENT_TAXONOMY[l1]) {
            this.form.patchValue({ l1 });
            if (l2 && INCIDENT_TAXONOMY[l1][l2]) {
                this.form.patchValue({ l2 });
                if (l3 && INCIDENT_TAXONOMY[l1][l2].includes(l3)) this.form.patchValue({ l3 });
            }
        }
    }

    l2Options(): string[] {
        const l1 = this.form.controls.l1.value ?? '';
        return l1 && INCIDENT_TAXONOMY[l1] ? Object.keys(INCIDENT_TAXONOMY[l1]) : [];
    }

    l3Options(): string[] {
        const l1 = this.form.controls.l1.value ?? '';
        const l2 = this.form.controls.l2.value ?? '';
        return l1 && l2 && INCIDENT_TAXONOMY[l1]?.[l2] ? INCIDENT_TAXONOMY[l1][l2] : [];
    }

    onL1(): void {
        this.form.patchValue({ l2: '', l3: '' });
    }

    onL2(): void {
        this.form.patchValue({ l3: '' });
    }

    apply(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.ref.close(joinCategory(v.l1!, v.l2!, v.l3!));
    }
}
