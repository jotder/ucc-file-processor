import { ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { AlertRule, AlertRuleUpsert, AlertsService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { pipelineOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { firstValueFrom } from 'rxjs';
import { QueryConditionGroupComponent } from 'app/inspecto/query/query-condition-group.component';
import { Condition, ColumnMeta, ConditionGroup, emptyGroup } from 'app/inspecto/query/query-types';
import { ALERT_RULE_ATTRIBUTES } from './alert-rule-attributes';

/** The ledger-row fields a `when` clause can scope on (the columns `AlertService`'s metric math
 *  reads) — fixed, unlike Decision Rule/Expectation's probed-from-a-store columns, since a ledger
 *  row's shape is the engine's own, not a target's records. */
const LEDGER_COLUMNS: ColumnMeta[] = [
    { name: 'status', type: 'string' },
    { name: 'total_input_rows', type: 'number' },
    { name: 'total_output_rows', type: 'number' },
    { name: 'rejected_count', type: 'number' },
    { name: 'duration_ms', type: 'number' },
    { name: 'start_time', type: 'date' },
    { name: 'end_time', type: 'date' },
];

/** The distinct fields an existing when-clause already references — seeds the column list with any
 *  field not in {@link LEDGER_COLUMNS} (forward-compatible with a ledger schema change). */
function referencedFields(group: ConditionGroup): string[] {
    const out: string[] = [];
    const walk = (item: Condition | ConditionGroup): void => {
        if (item.kind === 'group') item.items.forEach(walk);
        else if (item.field) out.push(item.field);
    };
    walk(group);
    return [...new Set(out)];
}

/** Dialog input: an existing rule ⇒ edit; absent ⇒ create. */
export interface AlertRuleFormData {
    rule?: AlertRule;
    /** Names already armed — on create the name control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
}
export interface AlertRuleFormResult {
    saved?: AlertRule;
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Create / edit an Alert Rule (audit C3) — fully spec-driven via `<inspecto-schema-form>`
 * ({@link ALERT_RULE_ATTRIBUTES}); an Alert Rule is flat scalars, so nothing is hand-built. Create
 * is two steps (ui-design-review R9 — name at save): the config step asks the metric/threshold; the
 * save step then asks the rule id. Mock-served until the real write endpoints land — a 503 surfaces
 * the writes-disabled banner (same contract as `job-form.dialog`).
 */
@Component({
    selector: 'app-alert-rule-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        QueryConditionGroupComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>
            {{ isEdit ? 'Edit alert rule — ' + data.rule?.name : step() === 'save' ? 'Save alert rule' : 'New alert rule' }}
        </h2>

        <mat-dialog-content class="!pt-2">
            @if (writesDisabled()) {
                <inspecto-alert class="mb-4 block" variant="warning" icon="heroicons_outline:lock-closed">
                    Alert-rule writes are disabled on this server (no write root configured).
                </inspecto-alert>
            }
            <!-- Config step content stays mounted (not @if'd) so the schema-form ViewChild survives the
                 step transition — only visually hidden via [hidden], never destroyed. -->
            <div [hidden]="step() === 'save'">
                <inspecto-schema-form #sf [specs]="attributes" [initial]="initialValue" [optionLoaders]="optionLoaders" (submitted)="save()"></inspecto-schema-form>

                <div class="mt-4 font-semibold">Only count batches matching (optional)</div>
                <inspecto-query-condition-group class="mt-2 block" [group]="when" [columns]="columns" [root]="true" />
                @if (whenEmpty()) {
                    <div class="text-secondary mt-1 text-sm">No conditions — every batch in the window counts.</div>
                }
            </div>
            @if (!isEdit && step() === 'save') {
                <!-- Save step (create only): the rule id, asked only now. -->
                <form [formGroup]="saveForm" aria-label="Name this alert rule" class="space-y-1">
                    <div class="text-secondary text-sm">Rule configured — give it a unique id to save it.</div>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Rule id</mat-label>
                        <input matInput formControlName="name" required cdkFocusInitial />
                        @if (saveForm.controls.name; as c) {
                            @if (c.hasError('required')) {
                                <mat-error>A rule id is required.</mat-error>
                            } @else if (c.hasError('pattern')) {
                                <mat-error>Start with a letter; then letters, digits, dash, underscore only.</mat-error>
                            } @else if (c.hasError('duplicate')) {
                                <mat-error>An alert rule with this id already exists.</mat-error>
                            }
                        }
                    </mat-form-field>
                </form>
            }
        </mat-dialog-content>

        <mat-dialog-actions align="end">
            @if (!isEdit && step() === 'save') {
                <button mat-button type="button" (click)="backToConfig()">Back</button>
            }
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" (click)="save()" [disabled]="saving()">
                {{ isEdit ? 'Save changes' : step() === 'save' ? 'Create rule' : 'Continue' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class AlertRuleFormDialog {
    private fb = inject(FormBuilder);
    private api = inject(AlertsService);
    private ref = inject(MatDialogRef<AlertRuleFormDialog, AlertRuleFormResult>);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    readonly data = inject<AlertRuleFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding a dirty form. */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => (this.schemaForm?.isDirty() ?? false) || this.saveForm.dirty,
        this.confirm,
    );

    /** Suggestion sources: metrics seen on armed rules + the engine's documented trio; pipeline scope. */
    readonly optionLoaders = {
        metric: async (): Promise<{ value: string; label: string }[]> => {
            const known = new Set(['error_rate', 'rejected_files', 'duration_ms']);
            try {
                for (const r of await firstValueFrom(this.api.rules())) if (r.metric) known.add(r.metric);
            } catch {
                // suggestions are best-effort — the documented trio still shows
            }
            return [...known].sort().map((m) => ({ value: m, label: m }));
        },
        onPipeline: pipelineOptionLoader(),
    };

    readonly isEdit = !!this.data.rule;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly attributes = ALERT_RULE_ATTRIBUTES;

    /** Create flow: `config` (metric/threshold) → `save` (rule id, asked last). Edit stays on `config`. */
    readonly step = signal<'config' | 'save'>('config');

    /** Save-step field (create only): the alert rule id — mirrors the schema-form `identifier` pattern
     *  it replaces (letter-start; letters/digits/dash/underscore). */
    readonly saveForm = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z][A-Za-z0-9_-]*$/),
                ...(this.data.existingNames?.length ? [uniqueNameValidator(this.data.existingNames)] : []),
            ],
        ],
    });

    readonly initialValue: Record<string, unknown> | undefined = this.data.rule
        ? { ...this.data.rule, onPipeline: this.data.rule.onPipeline ?? '' }
        : undefined;

    /** Deep-cloned on edit — the condition editor mutates the bound group in place. */
    readonly when: ConditionGroup = this.data.rule?.when ? structuredClone(this.data.rule.when) : emptyGroup('AND');
    /** Fixed ledger columns, plus any field an existing when-clause already references. */
    readonly columns: ColumnMeta[] = [
        ...LEDGER_COLUMNS,
        ...referencedFields(this.when)
            .filter((f) => !LEDGER_COLUMNS.some((c) => c.name === f))
            .map((name) => ({ name, type: 'string' as const })),
    ];

    whenEmpty(): boolean {
        return this.when.items.length === 0;
    }

    /** The suggested rule id: `<metric>_<comparator>_<window>`. */
    suggestedName(): string {
        const v = this.schemaForm.value() as { metric?: string; comparator?: string; window?: string };
        const base = [v.metric, v.comparator, v.window].filter(Boolean).join('_') || 'alert_rule';
        return base.replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^[^A-Za-z]+/, '');
    }

    /** Create flow only: leave the save step back to the config step (the id is kept). */
    backToConfig(): void {
        this.step.set('config');
    }

    save(): void {
        if (!this.schemaForm.validate()) return;
        // Create asks the rule id only now, at save time — config valid ⇒ advance to the save step.
        if (!this.isEdit && this.step() === 'config') {
            if (this.saveForm.controls.name.pristine) this.saveForm.patchValue({ name: this.suggestedName() });
            this.step.set('save');
            return;
        }
        if (!this.isEdit && this.saveForm.invalid) {
            this.saveForm.markAllAsTouched();
            return;
        }
        const v = this.schemaForm.value() as Partial<AlertRule>;
        const onPipeline = String(v.onPipeline ?? '').trim();
        const body: AlertRuleUpsert = {
            name: this.isEdit ? this.data.rule!.name : String(this.saveForm.getRawValue().name ?? '').trim(),
            metric: String(v.metric ?? '').trim(),
            comparator: String(v.comparator ?? 'gt'),
            threshold: Number(v.threshold ?? 0),
            window: String(v.window ?? '15m'),
            severity: String(v.severity ?? 'WARNING'),
            ...(onPipeline ? { onPipeline } : {}),
            ...(this.whenEmpty() ? {} : { when: this.when }),
        };
        this.saving.set(true);
        const call = this.isEdit ? this.api.updateRule(body.name, body) : this.api.createRule(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else this.toastr.error(apiErrorMessage(e, 'Could not save the alert rule.'));
            },
        });
    }
}
