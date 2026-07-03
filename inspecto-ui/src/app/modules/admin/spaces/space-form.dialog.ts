import { Component, inject } from '@angular/core';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, Space, SpacesService } from 'app/inspecto/api';

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Create + boot a new (empty) space. The id is the on-disk folder name and the request key, so it is
 * jailed to the backend's {@code SpaceId} charset: lowercase letters/digits/hyphen, not leading with a
 * hyphen, ≤ 63 chars. Submits POST /spaces and closes with the created {@link Space}.
 */
@Component({
    selector: 'app-space-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
    ],
    template: `
        <h2 mat-dialog-title>New space</h2>
        <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-dialog-content class="space-y-2">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Id</mat-label>
                    <input matInput formControlName="id" required autocomplete="off" />
                    <mat-hint>lowercase letters, digits and hyphens — becomes the space folder</mat-hint>
                    @if (form.get('id'); as c) {
                        @if (c.hasError('required')) {
                            <mat-error>Id is required.</mat-error>
                        } @else if (c.hasError('pattern')) {
                            <mat-error>Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.</mat-error>
                        } @else if (c.hasError('duplicate')) {
                            <mat-error>A space with this id already exists.</mat-error>
                        }
                    }
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Display name</mat-label>
                    <input matInput formControlName="display_name" autocomplete="off" />
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Description</mat-label>
                    <input matInput formControlName="description" autocomplete="off" />
                </mat-form-field>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button mat-dialog-close>Cancel</button>
                <button type="submit" mat-flat-button color="primary" [disabled]="form.invalid || saving">
                    Create
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class SpaceFormDialog {
    private fb = inject(FormBuilder);
    private api = inject(SpacesService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<SpaceFormDialog, Space>);

    saving = false;

    form: FormGroup = this.fb.group({
        id: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[a-z0-9][a-z0-9-]{0,62}$/),
                uniqueNameValidator(this.api.availableSpaces().map((s) => s.id)),
            ],
        ],
        display_name: [''],
        description: [''],
    });

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.saving = true;
        this.api
            .create({
                id: v.id,
                display_name: v.display_name || undefined,
                description: v.description || undefined,
            })
            .subscribe({
                next: (space) => {
                    this.saving = false;
                    this.toastr.success(`Space "${space.id}" created`);
                    this.ref.close(space);
                },
                error: (e) => {
                    this.saving = false;
                    const msg =
                        e?.status === 409
                            ? `A space "${v.id}" already exists.`
                            : apiErrorMessage(e, `Could not create "${v.id}".`);
                    this.toastr.error(msg);
                },
            });
    }
}
