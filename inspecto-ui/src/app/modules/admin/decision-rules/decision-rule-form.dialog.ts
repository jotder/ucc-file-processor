import { AfterViewInit, ChangeDetectionStrategy, Component, DestroyRef, inject, signal, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, DbBrowserService, DecisionRule, DecisionRulesService, DecisionRuleUpsert } from 'app/inspecto/api';
import {
    type Consequence,
    type ConsequenceInputSpec,
    type ConsequenceType,
    CONSEQUENCE_LABELS,
    PLATFORM_ACTIONS,
    ROUTING_ACTIONS,
    buildConsequence,
    consequenceDetail,
    consequenceInputSpec,
} from 'app/inspecto/decision';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { pipelineOrJobOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { QueryConditionGroupComponent } from 'app/inspecto/query/query-condition-group.component';
import { dbColumnType } from 'app/inspecto/query/query-columns';
import { Condition, ColumnMeta, ConditionGroup, emptyGroup } from 'app/inspecto/query/query-types';
import { DECISION_RULE_ATTRIBUTES } from './decision-rule-attributes';

/** Dialog input: an existing rule ⇒ edit; absent ⇒ create (optionally pre-filled from an AI proposal). */
export interface DecisionRuleFormData {
    rule?: DecisionRule;
    /** Ids already in use — on create the name control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
    /** Create-mode seed (R5) — an AI-proposed draft the human reviews and saves (the approval gate). */
    prefill?: Partial<DecisionRule>;
}
export interface DecisionRuleFormResult {
    saved?: DecisionRule;
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/** The distinct fields an existing when-clause already references (typed string until probed). */
function referencedFields(group: ConditionGroup): string[] {
    const out: string[] = [];
    const walk = (item: Condition | ConditionGroup): void => {
        if (item.kind === 'group') item.items.forEach(walk);
        else if (item.field) out.push(item.field);
    };
    walk(group);
    return [...new Set(out)];
}

/** The action select: the routing actions first, then the R5 platform actions. */
const ACTIONS: { value: ConsequenceType; label: string }[] = [...ROUTING_ACTIONS, ...PLATFORM_ACTIONS].map((a) => ({
    value: a,
    label: CONSEQUENCE_LABELS[a],
}));

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
        <h2 mat-dialog-title>
            {{ isEdit ? 'Edit decision rule "' + data.rule!.name + '"' : step() === 'save' ? 'Save decision rule' : 'New decision rule' }}
        </h2>
        <mat-dialog-content>
            @if (writesDisabled()) {
                <inspecto-alert variant="warning" title="Writes are disabled">
                    The server is running read-only — the rule was not saved.
                </inspecto-alert>
            }
            <!-- Config step content stays mounted (not @if'd) so the schema-form ViewChild survives the
                 step transition — only visually hidden via [hidden], never destroyed. -->
            <div [hidden]="step() === 'save'">
                <inspecto-schema-form [specs]="attributes" [initial]="initialValue" [optionLoaders]="optionLoaders" (submitted)="save()"></inspecto-schema-form>

                <div class="mt-4 font-semibold">When records match</div>
                <inspecto-query-condition-group class="mt-2 block" [group]="when" [columns]="columns()" [root]="true" />
                @if (!columns().length) {
                    <div class="text-secondary mt-1 text-sm">Field choices load from the target's records — set Target above first.</div>
                } @else if (whenEmpty()) {
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
                                @if (inputSpec(i); as d) {
                                    @if (d.show) {
                                        <mat-form-field class="gamma-mat-dense flex-auto" subscriptSizing="dynamic">
                                            <mat-label>{{ d.label }}</mat-label>
                                            <input matInput formControlName="detail" />
                                            @if (g.get('detail')?.hasError('required')) {
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
            </div>
            @if (!isEdit && step() === 'save') {
                <!-- Save step (create only): id + description, asked only now. -->
                <form [formGroup]="saveForm" aria-label="Name this decision rule" class="space-y-1">
                    <div class="text-secondary text-sm">Rule configured — give it a unique id to save it.</div>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Rule id</mat-label>
                        <input matInput formControlName="name" required cdkFocusInitial />
                        @if (saveForm.controls.name; as c) {
                            @if (c.hasError('required')) {
                                <mat-error>An id is required.</mat-error>
                            } @else if (c.hasError('pattern')) {
                                <mat-error>Start with a letter or digit; then letters, digits, <code>. _ -</code> only.</mat-error>
                            } @else if (c.hasError('duplicate')) {
                                <mat-error>A decision rule with this id already exists.</mat-error>
                            }
                        }
                    </mat-form-field>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Description</mat-label>
                        <textarea matInput formControlName="description" rows="2"></textarea>
                    </mat-form-field>
                </form>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (!isEdit && step() === 'save') {
                <button type="button" mat-button (click)="backToConfig()">Back</button>
            }
            <button type="button" mat-button (click)="requestClose()" [disabled]="saving()">Cancel</button>
            <button type="button" mat-flat-button color="primary" [disabled]="saving()" (click)="save()">
                {{ isEdit ? 'Save' : step() === 'save' ? 'Create' : 'Continue' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class DecisionRuleFormDialog implements AfterViewInit {
    private fb = inject(FormBuilder);
    private api = inject(DecisionRulesService);
    private db = inject(DbBrowserService);
    private destroyRef = inject(DestroyRef);
    private ref = inject(MatDialogRef<DecisionRuleFormDialog, DecisionRuleFormResult>);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    readonly data = inject<DecisionRuleFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding dirty scalars/consequences
     *  (the in-place `when` tree has no dirty tracking — edits there usually accompany the others). */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => (this.schemaForm?.isDirty() ?? false) || this.consequencesForm.dirty || this.saveForm.dirty,
        this.confirm,
    );

    /** Create flow: `config` (target/when/then) → `save` (id + description, asked last). Edit stays on `config`. */
    readonly step = signal<'config' | 'save'>('config');

    /** Save-step fields (create only): the decision-rule id IS the unique storage key; description optional. */
    readonly saveForm = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                ...(this.data.existingNames?.length ? [uniqueNameValidator(this.data.existingNames)] : []),
            ],
        ],
        description: [''],
    });

    /** Suggestion source: `target` follows the target-type picker. */
    readonly optionLoaders = { target: pipelineOrJobOptionLoader() };

    readonly isEdit = !!this.data.rule;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly attributes = DECISION_RULE_ATTRIBUTES;
    readonly actions = ACTIONS;

    /** Deep-cloned on edit — the condition editor mutates the bound group in place. */
    readonly when: ConditionGroup = this.data.rule
        ? structuredClone(this.data.rule.when)
        : this.data.prefill?.when
          ? structuredClone(this.data.prefill.when)
          : emptyGroup('AND');

    /** The when-clause field choices — probed from the chosen target's records (R2/R3 follow-up:
     *  the hardcoded CDR shape is gone). Seeded with the fields an existing rule already references
     *  so its conditions render before (or without) a successful probe. */
    readonly columns = signal<ColumnMeta[]>(this.seedColumns());

    private seedColumns(): ColumnMeta[] {
        return referencedFields(this.when).map((name) => ({ name, type: 'string' as const }));
    }

    /** Probe the target's store for its real columns (1 row); keep still-referenced unknown fields. */
    private loadColumns(target: string): void {
        const name = target.trim();
        if (!name) {
            this.columns.set(this.seedColumns());
            return;
        }
        this.db.table({ name, limit: 1 }).subscribe({
            next: (res) => {
                const fetched: ColumnMeta[] = res.columns.map((c) => ({ name: c.name, type: dbColumnType(c.type) }));
                const known = new Set(fetched.map((c) => c.name));
                this.columns.set([...fetched, ...this.seedColumns().filter((c) => !known.has(c.name))]);
            },
            error: () => this.columns.set(this.seedColumns()),
        });
    }

    readonly initialValue: Record<string, unknown> | undefined = this.buildInitial();

    private buildInitial(): Record<string, unknown> | undefined {
        const src = this.data.rule ?? this.data.prefill;
        if (!src) return undefined;
        return {
            targetType: src.targetType ?? 'pipeline',
            target: src.target ?? '',
            priority: src.priority ?? 100,
            enabled: src.enabled ?? true,
        };
    }

    readonly consequencesForm = this.fb.group({ consequences: this.fb.array<FormGroup>([]) });

    get consequencesArray(): FormArray<FormGroup> {
        return this.consequencesForm.controls.consequences;
    }

    constructor() {
        const existing = this.data.rule?.consequences ?? this.data.prefill?.consequences ?? [];
        if (existing.length) for (const c of existing) this.addConsequence(c);
        else this.addConsequence();
    }

    ngAfterViewInit(): void {
        if (this.isEdit) {
            this.saveForm.patchValue({ name: this.data.rule!.name, description: this.data.rule!.description ?? '' });
        }
        // The when-clause field choices follow the target the schema form holds.
        const target = this.schemaForm.form.get('target');
        target?.valueChanges
            .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
            .subscribe((t) => this.loadColumns(String(t ?? '')));
        this.loadColumns(String(target?.value ?? ''));
    }

    /** The suggested rule id: `<targetType>_<target>`. */
    suggestedName(): string {
        const v = this.schemaForm.value() as { targetType?: string; target?: string };
        const base = v.target ? `${v.targetType}_${v.target}` : 'decision_rule';
        return base.replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^[^A-Za-z0-9]+/, '');
    }

    /** Create flow only: leave the save step back to the config step (id/description are kept). */
    backToConfig(): void {
        this.step.set('config');
    }

    whenEmpty(): boolean {
        return this.when.items.length === 0;
    }

    inputSpec(i: number): ConsequenceInputSpec {
        return consequenceInputSpec(this.consequencesArray.at(i).get('action')?.value as ConsequenceType);
    }

    addConsequence(c?: Consequence): void {
        const g = this.fb.group({
            action: [c?.action ?? 'route'],
            detail: [c ? consequenceDetail(c) : ''],
        });
        // The detail field's requiredness follows the action (route/tag/target/param require one; quarantine optional; drop none).
        const applyRequired = (): void => {
            const spec = consequenceInputSpec(g.get('action')!.value as ConsequenceType);
            const detail = g.get('detail')!;
            detail.setValidators(spec.required ? [Validators.required] : []);
            detail.updateValueAndValidity({ emitEvent: false });
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
        // Create asks the id + description only now, at save time — config valid ⇒ advance.
        if (!this.isEdit && this.step() === 'config') {
            if (this.saveForm.controls.name.pristine) this.saveForm.patchValue({ name: this.suggestedName() });
            this.step.set('save');
            return;
        }
        if (!this.isEdit && this.saveForm.invalid) {
            this.saveForm.markAllAsTouched();
            return;
        }
        const v = this.schemaForm.value() as {
            targetType: 'pipeline' | 'job';
            target: string;
            priority?: number;
            enabled?: boolean;
        };
        const consequences: Consequence[] = this.consequencesArray.controls.map((g) =>
            buildConsequence(g.get('action')!.value as ConsequenceType, String(g.get('detail')!.value ?? '')),
        );
        const body: DecisionRuleUpsert = {
            name: this.isEdit ? this.data.rule!.name : String(this.saveForm.getRawValue().name ?? '').trim(),
            description: String(this.saveForm.getRawValue().description ?? '').trim(),
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
