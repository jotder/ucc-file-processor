import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, CreateObject, ObjectsService } from 'app/inspecto/api';
import { INCIDENT_TAXONOMY, joinCategory } from './incident-taxonomy';
import { INCIDENT_PRIORITIES } from './mail-model';

/**
 * Create dialog for an operator-created object (an INCIDENT or a CASE) — POST /objects.
 * An incident is created with its 3-layer categorization (GLOSSARY §9) and the
 * Critical/Major/Minor/Low priority ladder; tags are optional (CSV in `attributes.tags`).
 */
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

                @if (isIncident) {
                    <div class="flex gap-3">
                        <mat-form-field class="flex-1" subscriptSizing="dynamic">
                            <mat-label>Category</mat-label>
                            <mat-select formControlName="l1" required (selectionChange)="form.patchValue({ l2: '', l3: '' })">
                                @for (l1 of l1Options; track l1) {
                                    <mat-option [value]="l1">{{ l1 }}</mat-option>
                                }
                            </mat-select>
                            @if (form.controls.l1.hasError('required') && form.controls.l1.touched) {
                                <mat-error>Required.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field class="flex-1" subscriptSizing="dynamic">
                            <mat-label>Subcategory</mat-label>
                            <mat-select formControlName="l2" required (selectionChange)="form.patchValue({ l3: '' })">
                                @for (l2 of l2Options(); track l2) {
                                    <mat-option [value]="l2">{{ l2 }}</mat-option>
                                }
                            </mat-select>
                            @if (form.controls.l2.hasError('required') && form.controls.l2.touched) {
                                <mat-error>Required.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field class="flex-1" subscriptSizing="dynamic">
                            <mat-label>Detail</mat-label>
                            <mat-select formControlName="l3" required>
                                @for (l3 of l3Options(); track l3) {
                                    <mat-option [value]="l3">{{ l3 }}</mat-option>
                                }
                            </mat-select>
                            @if (form.controls.l3.hasError('required') && form.controls.l3.touched) {
                                <mat-error>Required.</mat-error>
                            }
                        </mat-form-field>
                    </div>
                }

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
                        <mat-select formControlName="priority">
                            @for (p of priorities; track p) {
                                <mat-option [value]="p">{{ p }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                </div>
                <div class="flex gap-3">
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>Assignee</mat-label>
                        <input matInput formControlName="assignee" />
                    </mat-form-field>
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>Tags</mat-label>
                        <input matInput formControlName="tags" placeholder="comma-separated, e.g. network,urgent" />
                    </mat-form-field>
                </div>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>SLA (minutes)</mat-label>
                    <input matInput type="number" formControlName="dueInMinutes" placeholder="optional" />
                </mat-form-field>
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

    readonly isIncident = this.data.type === 'INCIDENT';
    readonly priorities = INCIDENT_PRIORITIES;
    readonly l1Options = Object.keys(INCIDENT_TAXONOMY);

    saving = false;
    readonly form = this.fb.group({
        title: ['', Validators.required],
        description: [''],
        // 3-layer categorization — required for incidents only.
        l1: ['', this.isIncident ? Validators.required : []],
        l2: ['', this.isIncident ? Validators.required : []],
        l3: ['', this.isIncident ? Validators.required : []],
        severity: [''],
        priority: [''],
        assignee: [''],
        tags: [''],
        dueInMinutes: [null as number | null],
    });

    l2Options(): string[] {
        const l1 = this.form.controls.l1.value ?? '';
        return l1 && INCIDENT_TAXONOMY[l1] ? Object.keys(INCIDENT_TAXONOMY[l1]) : [];
    }

    l3Options(): string[] {
        const l1 = this.form.controls.l1.value ?? '';
        const l2 = this.form.controls.l2.value ?? '';
        return l1 && l2 && INCIDENT_TAXONOMY[l1]?.[l2] ? INCIDENT_TAXONOMY[l1][l2] : [];
    }

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
        const attributes: Record<string, string> = {};
        if (this.isIncident) attributes['category'] = joinCategory(v.l1!, v.l2!, v.l3!);
        const tags = (v.tags ?? '')
            .split(',')
            .map((t) => t.trim())
            .filter(Boolean)
            .join(',');
        if (tags) attributes['tags'] = tags;
        if (Object.keys(attributes).length) body.attributes = attributes;
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
