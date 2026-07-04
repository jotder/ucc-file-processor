import { AfterViewInit, ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    DecisionConsequence,
    DecisionConsequenceAction,
    DecisionRule,
    DecisionRulesService,
    DecisionRuleUpsert,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { QueryConditionGroupComponent } from 'app/inspecto/query/query-condition-group.component';
import { ColumnMeta, ConditionGroup, emptyGroup } from 'app/inspecto/query/query-types';
import { DECISION_RULE_ATTRIBUTES } from './decision-rule-attributes';

/** Dialog input: an existing rule ⇒ edit; absent ⇒ create. */
export interface DecisionRuleFormData {
    rule?: DecisionRule;
    /** Ids already in use — on create the name control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
}
export interface DecisionRuleFormResult {
    saved?: DecisionRule;
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/** The record fields offered by the when-clause field select (the seeded CDR shape; free typing is a follow-up). */
const RECORD_COLUMNS: ColumnMeta[] = [
    { name: 'id', type: 'number' },
    { name: 'msisdn', type: 'string' },
    { name: 'duration_s', type: 'number' },
    { name: 'bytes_used', type: 'number' },
    { name: 'cost_usd', type: 'number' },
    { name: 'tariff', type: 'string' },
    { name: 'event_time', type: 'date' },
];

const ACTIONS: { value: DecisionConsequenceAction; label: string }[] = [
    { value: 'route', label: 'Route to branch' },
    { value: 'tag', label: 'Tag record' },
    { value: 'quarantine', label: 'Quarantine' },
    { value: 'drop', label: 'Drop' },
];

/** Whether an action's destination field applies, and what to call it. */
export function destinationSpec(action: DecisionConsequenceAction): { show: boolean; label: string; required: boolean } {
    switch (action) {
        case 'route': return { show: true, label: 'Branch', required: true };
        case 'tag': return { show: true, label: 'Tag value', required: true };
        case 'quarantine': return { show: true, label: 'Reason (optional)', required: false };
        case 'drop': return { show: false, label: '', required: false };
    }
}

/**
 * Create / edit a Decision Rule (C3) — scalars via `<inspecto-schema-form>`
 * ({@link DECISION_RULE_ATTRIBUTES}); the WHEN clause reuses the shared recursive condition-tree
 * editor; the THEN consequences are a bespoke FormArray (action + destination per row, ≥ 1 row).
 * The id is immutable on edit.
 */
@Component({
    selector: 'app-decision-rule-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        QueryConditionGroupComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ isEdit ? 'Edit decision rule "' + data.rule!.name + '"' : 'New decision rule' }}</h2>
        <mat-dialog-content>
            @if (writesDisabled()) {
                <inspecto-alert variant="warning" title="Writes are disabled">
                    The server is running read-only — the rule was not saved.
                </inspecto-alert>
            }
            <inspecto-schema-form [specs]="attributes" [initial]="initialValue"></inspecto-schema-form>

            <div class="mt-4 font-semibold">When records match</div>
            <inspecto-query-condition-group class="mt-2 block" [group]="when" [columns]="columns" [root]="true" />
            @if (whenEmpty()) {
                <div class="text-secondary mt-1 text-sm">No conditions yet — the rule would match every record.</div>
            }

            <div class="mt-4 font-semibold">Then</div>
            <div class="mt-2 flex flex-col gap-2" [formGroup]="consequencesForm">
                <div formArrayName="consequences" class="flex flex-col gap-2">
                    @for (g of consequencesArray.controls; track g; let i = $index) {
                        <div [formGroupName]="i" class="flex items-center gap-3">
                            <mat-form-field class="gamma-mat-dense w-48" subscriptSizing="dynamic">
                                <mat-label>Action</mat-label>
                                <mat-select formControlName="action">
                                    @for (a of actions; track a.value) {
                                        <mat-option [value]="a.value">{{ a.label }}</mat-option>
                                    }
                                </mat-select>
                            </mat-form-field>
                            @if (destSpec(i); as d) {
                                @if (d.show) {
                                    <mat-form-field class="gamma-mat-dense flex-auto" subscriptSizing="dynamic">
                                        <mat-label>{{ d.label }}</mat-label>
                                        <input matInput formControlName="destination" />
                                        @if (g.get('destination')?.hasError('required')) {
                                            <mat-error>Required for this action.</mat-error>
                                        }
                                    </mat-form-field>
                                }
                            }
                            <button type="button" mat-icon-button (click)="removeConsequence(i)"
                                    [disabled]="consequencesArray.length === 1"
                                    matTooltip="Remove consequence" aria-label="Remove consequence">
                                <mat-icon class="icon-size-5" svgIcon="heroicons_outline:trash"></mat-icon>
                            </button>
                        </div>
                    }
                </div>
                <div>
                    <button type="button" mat-stroked-button (click)="addConsequence()">
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                        <span class="ml-1">Add consequence</span>
                    </button>
                </div>
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close [disabled]="saving()">Cancel</button>
            <button type="button" mat-flat-button color="primary" [disabled]="saving()" (click)="save()">
                {{ isEdit ? 'Save' : 'Create' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class DecisionRuleFormDialog implements AfterViewInit {
    private fb = inject(FormBuilder);
    private api = inject(DecisionRulesService);
    private ref = inject(MatDialogRef<DecisionRuleFormDialog, DecisionRuleFormResult>);
    private toastr = inject(ToastrService);
    readonly data = inject<DecisionRuleFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly isEdit = !!this.data.rule;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly attributes = DECISION_RULE_ATTRIBUTES;
    readonly columns = RECORD_COLUMNS;
    readonly actions = ACTIONS;

    /** Deep-cloned on edit — the condition editor mutates the bound group in place. */
    readonly when: ConditionGroup = this.data.rule ? structuredClone(this.data.rule.when) : emptyGroup('AND');

    readonly initialValue: Record<string, unknown> | undefined = this.data.rule
        ? {
              name: this.data.rule.name,
              description: this.data.rule.description ?? '',
              targetType: this.data.rule.targetType,
              target: this.data.rule.target,
              priority: this.data.rule.priority,
              enabled: this.data.rule.enabled,
          }
        : undefined;

    readonly consequencesForm = this.fb.group({ consequences: this.fb.array<FormGroup>([]) });

    get consequencesArray(): FormArray<FormGroup> {
        return this.consequencesForm.controls.consequences;
    }

    constructor() {
        const existing = this.data.rule?.consequences ?? [];
        if (existing.length) for (const c of existing) this.addConsequence(c);
        else this.addConsequence();
    }

    ngAfterViewInit(): void {
        const nameCtrl = this.schemaForm.form.get('name');
        if (this.isEdit) {
            // The id is immutable once created (it is the storage key).
            nameCtrl?.disable({ emitEvent: false });
        } else if (this.data.existingNames?.length) {
            // Block a duplicate id inline rather than relying on the server 409 (product-wide form rule).
            nameCtrl?.addValidators(uniqueNameValidator(this.data.existingNames));
            nameCtrl?.updateValueAndValidity({ emitEvent: false });
        }
    }

    whenEmpty(): boolean {
        return this.when.items.length === 0;
    }

    destSpec(i: number): { show: boolean; label: string; required: boolean } {
        return destinationSpec(this.consequencesArray.at(i).get('action')?.value as DecisionConsequenceAction);
    }

    addConsequence(c?: DecisionConsequence): void {
        const g = this.fb.group({
            action: [c?.action ?? 'route'],
            destination: [c?.destination ?? ''],
        });
        // Destination requiredness follows the action (route/tag require one, quarantine optional, drop none).
        const applyRequired = (): void => {
            const d = destinationSpec(g.get('action')!.value as DecisionConsequenceAction);
            const dest = g.get('destination')!;
            dest.setValidators(d.required ? [Validators.required] : []);
            dest.updateValueAndValidity({ emitEvent: false });
        };
        g.get('action')!.valueChanges.subscribe(applyRequired);
        applyRequired();
        this.consequencesArray.push(g);
    }

    removeConsequence(i: number): void {
        if (this.consequencesArray.length > 1) this.consequencesArray.removeAt(i);
    }

    save(): void {
        const consequencesValid = this.consequencesForm.valid;
        if (!this.schemaForm.validate() || !consequencesValid) {
            this.consequencesForm.markAllAsTouched();
            return;
        }
        const v = this.schemaForm.value() as {
            name?: string;
            description?: string;
            targetType: 'pipeline' | 'job';
            target: string;
            priority?: number;
            enabled?: boolean;
        };
        const consequences: DecisionConsequence[] = this.consequencesArray.controls.map((g) => {
            const action = g.get('action')!.value as DecisionConsequenceAction;
            const d = destinationSpec(action);
            const raw = String(g.get('destination')!.value ?? '').trim();
            return { action, destination: d.show && raw ? raw : null };
        });
        const body: DecisionRuleUpsert = {
            name: this.isEdit ? this.data.rule!.name : String(v.name ?? '').trim(),
            description: String(v.description ?? '').trim(),
            targetType: v.targetType,
            target: String(v.target ?? '').trim(),
            when: this.when,
            consequences,
            priority: v.priority ?? 100,
            enabled: v.enabled !== false,
        };
        this.saving.set(true);
        const call = this.isEdit ? this.api.update(body.name, body) : this.api.create(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else this.toastr.error(apiErrorMessage(e, 'Could not save the decision rule.'));
            },
        });
    }
}
