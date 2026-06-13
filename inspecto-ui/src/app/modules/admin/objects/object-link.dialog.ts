import { Component, inject, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import { ObjectsService, OperationalObject } from 'app/inspecto/api';

/** Create a correlation link from one object to another — POST /objects/{id}/links. */
@Component({
    selector: 'app-object-link-dialog',
    standalone: true,
    imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatSelectModule],
    template: `
        <h2 mat-dialog-title>Link this {{ data.fromType }}</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2" style="min-width: 26rem">
            <mat-form-field subscriptSizing="dynamic">
                <mat-label>Relationship</mat-label>
                <mat-select [(ngModel)]="relationship">
                    <mat-option value="CONTAINS">CONTAINS</mat-option>
                    <mat-option value="ESCALATED_FROM">ESCALATED_FROM</mat-option>
                    <mat-option value="CAUSED_BY">CAUSED_BY</mat-option>
                    <mat-option value="RELATED_TO">RELATED_TO</mat-option>
                </mat-select>
            </mat-form-field>
            <mat-form-field subscriptSizing="dynamic">
                <mat-label>Target object</mat-label>
                <mat-select [(ngModel)]="to">
                    @for (o of candidates; track o.id) {
                        <mat-option [value]="o.id">{{ o.objectType }} · {{ o.title || o.id }} ({{ o.status }})</mat-option>
                    }
                </mat-select>
            </mat-form-field>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="null">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="!to || saving" (click)="save()">Link</button>
        </mat-dialog-actions>
    `,
})
export class ObjectLinkDialog implements OnInit {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<ObjectLinkDialog>);
    private toastr = inject(ToastrService);
    readonly data = inject<{ fromId: string; fromType: string }>(MAT_DIALOG_DATA);

    candidates: OperationalObject[] = [];
    to = '';
    relationship = 'RELATED_TO';
    saving = false;

    ngOnInit(): void {
        this.api.list({ limit: 200 }).subscribe({
            next: (os) => (this.candidates = os.filter((o) => o.id !== this.data.fromId)),
            error: () => (this.candidates = []),
        });
    }

    save(): void {
        if (!this.to) return;
        this.saving = true;
        this.api.link(this.data.fromId, this.to, this.relationship).subscribe({
            next: (l) => {
                this.toastr.success(`Linked: ${l.relationship} → ${l.to}`);
                this.ref.close(l);
            },
            error: (e) => {
                this.saving = false;
                this.toastr.error(e?.error?.error ?? 'Link failed');
            },
        });
    }
}
