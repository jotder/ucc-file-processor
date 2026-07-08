import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { BiTemplate } from 'app/inspecto/api';

export interface ApplyTemplateData {
    template: BiTemplate;
    /** Dataset ids to choose the target from (the template's components bind to it). */
    datasetIds: string[];
}

/** Chosen target: which dataset to bind, and an optional id prefix to avoid collisions on re-apply. */
export interface ApplyTemplateResult {
    dataset: string;
    prefix?: string;
}

/**
 * Apply-template dialog (BI-8 gallery): pick the Dataset the curated components bind to, plus an optional
 * prefix so the same template can be applied twice without id collisions. Closes with the
 * {@link ApplyTemplateResult} (or undefined on cancel); the caller performs the apply call.
 */
@Component({
    selector: 'app-apply-template-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Apply “{{ data.template.title }}”</h2>
        <mat-dialog-content>
            <p class="text-secondary mb-3 text-sm">{{ data.template.description }}</p>
            <form [formGroup]="form" (ngSubmit)="apply()" class="flex flex-col gap-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Target dataset</mat-label>
                    <mat-select formControlName="dataset">
                        @for (id of data.datasetIds; track id) {
                            <mat-option [value]="id">{{ id }}</mat-option>
                        }
                    </mat-select>
                    @if (form.controls.dataset.hasError('required') && form.controls.dataset.touched) {
                        <mat-error>Choose a dataset to bind the template to.</mat-error>
                    }
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Id prefix (optional)</mat-label>
                    <input matInput formControlName="prefix" placeholder="e.g. q3" />
                    @if (form.controls.prefix.hasError('pattern')) {
                        <mat-error>Letters, digits, dash, underscore only.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="apply()">Apply</button>
        </mat-dialog-actions>
    `,
})
export class ApplyTemplateDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<ApplyTemplateDialog, ApplyTemplateResult>);
    readonly data = inject<ApplyTemplateData>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        dataset: ['', Validators.required],
        prefix: ['', Validators.pattern(/^[A-Za-z0-9_-]*$/)],
    });

    apply(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const prefix = String(this.form.controls.prefix.value ?? '').trim();
        this.ref.close({ dataset: this.form.controls.dataset.value!, prefix: prefix || undefined });
    }
}
