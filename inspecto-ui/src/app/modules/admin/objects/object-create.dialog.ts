import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import { CreateObject, ObjectsService } from 'app/inspecto/api';

/** Create dialog for an operator-created object (an ISSUE or a CASE) — POST /objects. */
@Component({
    selector: 'app-object-create-dialog',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
    ],
    template: `
        <h2 mat-dialog-title>New {{ data.label }}</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2">
            <mat-form-field subscriptSizing="dynamic">
                <mat-label>Title</mat-label>
                <input matInput [(ngModel)]="form.title" placeholder="short summary" />
            </mat-form-field>
            <mat-form-field subscriptSizing="dynamic">
                <mat-label>Description</mat-label>
                <textarea matInput rows="3" [(ngModel)]="form.description"></textarea>
            </mat-form-field>
            <div class="flex gap-3">
                <mat-form-field class="flex-1" subscriptSizing="dynamic">
                    <mat-label>Severity</mat-label>
                    <mat-select [(ngModel)]="form.severity">
                        <mat-option value="INFO">INFO</mat-option>
                        <mat-option value="WARNING">WARNING</mat-option>
                        <mat-option value="CRITICAL">CRITICAL</mat-option>
                    </mat-select>
                </mat-form-field>
                <mat-form-field class="flex-1" subscriptSizing="dynamic">
                    <mat-label>Priority</mat-label>
                    <input matInput [(ngModel)]="form.priority" placeholder="e.g. P1" />
                </mat-form-field>
            </div>
            <div class="flex gap-3">
                <mat-form-field class="flex-1" subscriptSizing="dynamic">
                    <mat-label>Assignee</mat-label>
                    <input matInput [(ngModel)]="form.assignee" />
                </mat-form-field>
                <mat-form-field class="flex-1" subscriptSizing="dynamic">
                    <mat-label>SLA (minutes)</mat-label>
                    <input matInput type="number" [(ngModel)]="form.dueInMinutes" placeholder="optional" />
                </mat-form-field>
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="null">Cancel</button>
            <button
                mat-flat-button
                color="primary"
                [disabled]="!form.title || saving"
                (click)="save()"
            >
                Create
            </button>
        </mat-dialog-actions>
    `,
})
export class ObjectCreateDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<ObjectCreateDialog>);
    private toastr = inject(ToastrService);
    readonly data = inject<{ type: string; label: string }>(MAT_DIALOG_DATA);

    saving = false;
    form: CreateObject = { type: this.data.type, title: '' };

    save(): void {
        if (!this.form.title) return;
        this.saving = true;
        const body: CreateObject = { ...this.form, type: this.data.type };
        if (!body.dueInMinutes) delete body.dueInMinutes;
        this.api.create(body).subscribe({
            next: (o) => {
                this.toastr.success(`Created ${o.objectType} ${o.id}`);
                this.ref.close(o);
            },
            error: (e) => {
                this.saving = false;
                this.toastr.error(e?.error?.error ?? 'Create failed');
            },
        });
    }
}
