import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

/**
 * Resolve dialog — the state change to Resolved requires a resolution comment (GLOSSARY §9);
 * the comment is appended to each selected object's thread before the `resolve` transition.
 * Closes with the comment text, or null on cancel.
 */
@Component({
    selector: 'app-resolve-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
    template: `
        <h2 mat-dialog-title>Resolve {{ data.count }} {{ data.label }}{{ data.count === 1 ? '' : 's' }}</h2>
        <mat-dialog-content class="pt-2">
            <form [formGroup]="form">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Resolution</mat-label>
                    <textarea
                        matInput
                        rows="4"
                        formControlName="comment"
                        placeholder="what was done, and why the problem is considered fixed"
                        required
                        cdkFocusInitial
                    ></textarea>
                    @if (form.controls.comment.hasError('required') && form.controls.comment.touched) {
                        <mat-error>A resolution comment is required.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="null">Cancel</button>
            <button mat-flat-button color="primary" (click)="apply()">Resolve</button>
        </mat-dialog-actions>
    `,
})
export class ResolveDialog {
    private ref = inject(MatDialogRef<ResolveDialog>);
    private fb = inject(FormBuilder);
    readonly data = inject<{ count: number; label: string }>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({ comment: ['', Validators.required] });

    apply(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close(this.form.getRawValue().comment);
    }
}
