import { Component, inject, OnInit } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject } from 'app/inspecto/api';

/** Create a correlation link from one object to another — POST /objects/{id}/links. */
@Component({
    selector: 'app-object-link-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatSelectModule],
    template: `
        <h2 mat-dialog-title>Link this {{ data.fromType }}</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2" style="min-width: 26rem">
            <form [formGroup]="form" class="flex flex-col gap-3">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Relationship</mat-label>
                    <mat-select formControlName="relationship" cdkFocusInitial>
                        <mat-option value="CONTAINS">CONTAINS</mat-option>
                        <mat-option value="ESCALATED_FROM">ESCALATED_FROM</mat-option>
                        <mat-option value="CAUSED_BY">CAUSED_BY</mat-option>
                        <mat-option value="RELATED_TO">RELATED_TO</mat-option>
                    </mat-select>
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Target object</mat-label>
                    <mat-select formControlName="to" required>
                        @for (o of candidates; track o.id) {
                            <mat-option [value]="o.id">{{ o.objectType }} · {{ o.title || o.id }} ({{ o.status }})</mat-option>
                        }
                    </mat-select>
                    @if (form.controls.to.hasError('required') && form.controls.to.touched) {
                        <mat-error>Pick a target object to link to.</mat-error>
                    }
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="null">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving" (click)="save()">Link</button>
        </mat-dialog-actions>
    `,
})
export class ObjectLinkDialog implements OnInit {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<ObjectLinkDialog>);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);
    readonly data = inject<{ fromId: string; fromType: string }>(MAT_DIALOG_DATA);

    candidates: OperationalObject[] = [];
    saving = false;
    readonly form = this.fb.group({
        relationship: ['RELATED_TO', Validators.required],
        to: ['', Validators.required],
    });

    ngOnInit(): void {
        this.api.list({ limit: 200 }).subscribe({
            next: (os) => (this.candidates = os.filter((o) => o.id !== this.data.fromId)),
            error: () => (this.candidates = []),
        });
    }

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.saving = true;
        const { to, relationship } = this.form.getRawValue();
        this.api.link(this.data.fromId, to, relationship).subscribe({
            next: (l) => {
                this.toastr.success(`Linked: ${l.relationship} → ${l.to}`);
                this.ref.close(l);
            },
            error: (e) => {
                this.saving = false;
                this.toastr.error(apiErrorMessage(e, 'Link failed'));
            },
        });
    }
}
