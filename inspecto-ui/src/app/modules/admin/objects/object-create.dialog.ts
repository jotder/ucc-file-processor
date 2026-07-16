import { Component, ElementRef, inject, signal, ViewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule, MatAutocompleteSelectedEvent } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatChipInputEvent, MatChipsModule } from '@angular/material/chips';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { COMMA, ENTER } from '@angular/cdk/keycodes';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, CreateObject, ObjectsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { INCIDENT_TAXONOMY, joinCategory } from './incident-taxonomy';
import { currentOperator, INCIDENT_PRIORITIES } from './mail-model';

/**
 * Create dialog for an operator-created object (an INCIDENT or a CASE) — POST /objects.
 * An incident is created with its 3-layer categorization (GLOSSARY §9) and the
 * Critical/Major/Minor/Low priority ladder; tags are chips suggested from the tag registry
 * (stored as CSV in `attributes.tags`), the assignee suggests me + the mailbox's known
 * assignees (ui-design-review R2 follow-up) — free text stays valid for both.
 */
@Component({
    selector: 'app-object-create-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatAutocompleteModule,
        MatButtonModule,
        MatChipsModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
    ],
    template: `
        <h2 mat-dialog-title>New {{ data.label }}</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2">
            <form [formGroup]="form" class="flex flex-col gap-3">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Title</mat-label>
                    <input matInput formControlName="title" placeholder="short summary" required cdkFocusInitial />
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
                        <input matInput formControlName="assignee" [matAutocomplete]="assigneeAc" />
                        <mat-autocomplete #assigneeAc="matAutocomplete">
                            @for (a of assigneeOptions(); track a) {
                                <mat-option [value]="a">{{ a }}</mat-option>
                            }
                        </mat-autocomplete>
                    </mat-form-field>
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>Tags</mat-label>
                        <mat-chip-grid #tagGrid aria-label="Tags">
                            @for (t of tags(); track t) {
                                <mat-chip-row (removed)="removeTag(t)">
                                    {{ t }}
                                    <button matChipRemove [attr.aria-label]="'Remove ' + t">
                                        <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                                    </button>
                                </mat-chip-row>
                            }
                            <input
                                #tagInput
                                placeholder="e.g. network, urgent"
                                [matChipInputFor]="tagGrid"
                                [matChipInputSeparatorKeyCodes]="tagSeparatorKeys"
                                [matAutocomplete]="tagAc"
                                (matChipInputTokenEnd)="addTag($event)"
                                (input)="tagQuery.set($any($event.target).value)"
                            />
                        </mat-chip-grid>
                        <mat-autocomplete #tagAc="matAutocomplete" (optionSelected)="pickTag($event)">
                            @for (t of tagSuggestions(); track t) {
                                <mat-option [value]="t">{{ t }}</mat-option>
                            }
                        </mat-autocomplete>
                    </mat-form-field>
                </div>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>SLA (minutes)</mat-label>
                    <input matInput type="number" formControlName="dueInMinutes" placeholder="optional" />
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving" (click)="save()">Create</button>
        </mat-dialog-actions>
    `,
})
export class ObjectCreateDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<ObjectCreateDialog>);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);
    readonly data = inject<{ type: string; label: string; assignees?: string[] }>(MAT_DIALOG_DATA);

    readonly isIncident = this.data.type === 'INCIDENT';
    readonly priorities = INCIDENT_PRIORITIES;
    readonly l1Options = Object.keys(INCIDENT_TAXONOMY);
    readonly tagSeparatorKeys = [ENTER, COMMA] as const;

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding entered data. */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty || this.tags().length > 0, this.confirm);

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
        dueInMinutes: [null as number | null],
    });

    /** The chosen tags (chips) and the registry the input suggests from. */
    readonly tags = signal<string[]>([]);
    readonly tagQuery = signal('');
    private readonly registry = signal<string[]>([]);

    /** Suggestions: registry tags not yet chosen, narrowed by the input's text. */
    readonly tagSuggestions = (): string[] => {
        const q = this.tagQuery().trim().toLowerCase();
        const chosen = new Set(this.tags());
        return this.registry().filter((t) => !chosen.has(t) && (!q || t.toLowerCase().includes(q)));
    };

    /** Assignee suggestions: me + the mailbox's known assignees, narrowed by the typed text. */
    readonly assigneeOptions = (): string[] => {
        const q = String(this.form.controls.assignee.value ?? '').trim().toLowerCase();
        const all = [...new Set([currentOperator(), ...(this.data.assignees ?? [])])].sort();
        return q ? all.filter((a) => a.toLowerCase().includes(q)) : all;
    };

    constructor() {
        this.api.tags().subscribe({ next: (t) => this.registry.set(t.map((x) => x.name)), error: () => undefined });
    }

    addTag(event: MatChipInputEvent): void {
        const value = event.value.trim();
        if (value && !this.tags().includes(value)) {
            this.tags.update((t) => [...t, value]);
            this.form.markAsDirty();
        }
        event.chipInput?.clear();
        this.tagQuery.set('');
    }

    @ViewChild('tagInput') private tagInput?: ElementRef<HTMLInputElement>;

    pickTag(event: MatAutocompleteSelectedEvent): void {
        const value = String(event.option.value ?? '').trim();
        if (value && !this.tags().includes(value)) {
            this.tags.update((t) => [...t, value]);
            this.form.markAsDirty();
        }
        event.option.deselect();
        if (this.tagInput) this.tagInput.nativeElement.value = '';
        this.tagQuery.set('');
    }

    removeTag(value: string): void {
        this.tags.update((t) => t.filter((x) => x !== value));
        this.form.markAsDirty();
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
        const tags = this.tags().join(',');
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
