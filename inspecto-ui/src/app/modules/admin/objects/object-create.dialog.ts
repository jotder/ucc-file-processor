import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, CreateObject, ObjectsService } from 'app/inspecto/api';

/** Create dialog for an operator-created object (an INCIDENT or a CASE) — POST /objects. */
@Component({
    selector: 'app-object-create-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
    ],
    template: `
        <h2 mat-dialog-title>New {{ data.label }}</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2">
            <form [formGroup]="form" class="flex flex-col gap-3">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Title</mat-label>
                    <input matInput formControlName="title" placeholder="short summary" required />
                    @if (form.controls.title.hasError('required') && form.controls.title.touched) {
                        <mat-error>Title is required.</mat-error>
                    }
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Description</mat-label>
                    <textarea matInput rows="3" formControlName="description"></textarea>
                </mat-form-field>
                <div class="flex gap-3">
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>Severity</mat-label>
                        <mat-select formControlName="severity">
                            <mat-option value="INFO">INFO</mat-option>
                            <mat-option value="WARNING">WARNING</mat-option>
                            <mat-option value="CRITICAL">CRITICAL</mat-option>
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>Priority</mat-label>
                        <input matInput formControlName="priority" placeholder="e.g. P1" />
                    </mat-form-field>
                </div>
                <div class="flex gap-3">
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>Assignee</mat-label>
                        <input matInput formControlName="assignee" />
                    </mat-form-field>
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>SLA (minutes)</mat-label>
                        <input matInput type="number" formControlName="dueInMinutes" placeholder="optional" />
                    </mat-form-field>
                </div>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="null">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving" (click)="save()">Create</button>
        </mat-dialog-actions>
    `,
})
export class ObjectCreateDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<ObjectCreateDialog>);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);
    readonly data = inject<{ type: string; label: string }>(MAT_DIALOG_DATA);

    saving = false;
    readonly form = this.fb.group({
        title: ['', Validators.required],
        description: [''],
        severity: [''],
        priority: [''],
        assignee: [''],
        dueInMinutes: [null as number | null],
    });

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.saving = true;
        const v = this.form.getRawValue();
        const body: CreateObject = { type: this.data.type, title: v.title };
        if (v.description) body.description = v.description;
        if (v.severity) body.severity = v.severity;
        if (v.priority) body.priority = v.priority;
        if (v.assignee) body.assignee = v.assignee;
        if (v.dueInMinutes) body.dueInMinutes = v.dueInMinutes;
        this.api.create(body).subscribe({
            next: (o) => {
                this.toastr.success(`Created ${o.objectType} ${o.id}`);
                this.ref.close(o);
            },
            error: (e) => {
                this.saving = false;
                this.toastr.error(apiErrorMessage(e, 'Create failed'));
            },
        });
    }
}
