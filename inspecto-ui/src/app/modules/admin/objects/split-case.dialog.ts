import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectGraphNode, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { currentOperator } from './mail-model';

/**
 * <b>Split</b> a Case (C2, GLOSSARY §9): tick the member incidents to carve out into a new case
 * managed individually. Closes with `true` after a successful split.
 */
@Component({
    selector: 'app-split-case-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule, MatInputModule],
    template: `
        <h2 mat-dialog-title>Split "{{ data.caseObj.title }}"</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2">
            <mat-form-field subscriptSizing="dynamic">
                <mat-label>New case title</mat-label>
                <input matInput [formControl]="form.controls.title" placeholder="what the carved-out part is about" required cdkFocusInitial />
                @if (form.controls.title.hasError('required') && form.controls.title.touched) {
                    <mat-error>A title is required.</mat-error>
                }
            </mat-form-field>
            <mat-form-field subscriptSizing="dynamic">
                <mat-label>Assignee (optional)</mat-label>
                <input matInput [formControl]="form.controls.assignee" />
            </mat-form-field>

            <div>
                <div class="mb-1 text-xs font-bold uppercase tracking-wider opacity-60">Members to move</div>
                @for (n of data.members; track n.id) {
                    <mat-checkbox class="block" [checked]="picked().has(n.id)" (change)="toggle(n.id, $event.checked)">
                        {{ n.title }} <span class="text-secondary text-xs">({{ n.id }})</span>
                    </mat-checkbox>
                }
                @if (nonePicked()) {
                    <p class="text-secondary text-sm" role="alert">Tick at least one member to move.</p>
                }
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="false">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving()" (click)="split()">Split</button>
        </mat-dialog-actions>
    `,
})
export class SplitCaseDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<SplitCaseDialog>);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);
    readonly data = inject<{ caseObj: OperationalObject; members: ObjectGraphNode[] }>(MAT_DIALOG_DATA);

    readonly form = this.fb.group({
        title: ['', Validators.required],
        assignee: [''],
    });
    readonly picked = signal(new Set<string>());
    readonly nonePicked = signal(false);
    readonly saving = signal(false);

    toggle(id: string, on: boolean): void {
        this.picked.update((s) => {
            const next = new Set(s);
            if (on) next.add(id);
            else next.delete(id);
            return next;
        });
        this.nonePicked.set(false);
    }

    split(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        if (!this.picked().size) {
            this.nonePicked.set(true);
            return;
        }
        this.saving.set(true);
        const v = this.form.getRawValue();
        this.api.splitCase(this.data.caseObj.id, {
            title: v.title!.trim(),
            members: [...this.picked()],
            ...(v.assignee?.trim() ? { assignee: v.assignee.trim() } : {}),
            actor: currentOperator(),
        }).subscribe({
            next: (res) => {
                this.toastr.success(`Split ${res.membersMoved} member(s) into ${res.case.id} ("${res.case.title}")`);
                this.ref.close(true);
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Split failed'));
            },
        });
    }
}
