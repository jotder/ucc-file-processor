import { AfterViewInit, ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, ValidatorFn } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { AlertRule, AlertRuleUpsert, AlertsService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { ALERT_RULE_ATTRIBUTES } from './alert-rule-attributes';

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
 * ({@link ALERT_RULE_ATTRIBUTES}); an Alert Rule is flat scalars, so nothing is hand-built.
 * Mock-served until the real write endpoints land — a 503 surfaces the writes-disabled banner
 * (same contract as `job-form.dialog`).
 */
@Component({
    selector: 'app-alert-rule-form-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, InspectoAlertComponent, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ isEdit ? 'Edit alert rule — ' + data.rule?.name : 'New alert rule' }}</h2>

        <mat-dialog-content class="!pt-2">
            @if (writesDisabled()) {
                <inspecto-alert class="mb-4 block" variant="warning" icon="heroicons_outline:lock-closed">
                    Alert-rule writes are disabled on this server (no write endpoint yet) — rules are
                    armed by saving a <code>*_alert.toon</code> next to the pipeline configs.
                </inspecto-alert>
            }
            <inspecto-schema-form #sf [specs]="attributes" [initial]="initialValue"></inspecto-schema-form>
        </mat-dialog-content>

        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Cancel</button>
            <button mat-flat-button color="primary" (click)="save()" [disabled]="saving()">
                {{ isEdit ? 'Save changes' : 'Create rule' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class AlertRuleFormDialog implements AfterViewInit {
    private api = inject(AlertsService);
    private ref = inject(MatDialogRef<AlertRuleFormDialog, AlertRuleFormResult>);
    private toastr = inject(ToastrService);
    readonly data = inject<AlertRuleFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly isEdit = !!this.data.rule;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly attributes = ALERT_RULE_ATTRIBUTES;

    readonly initialValue: Record<string, unknown> | undefined = this.data.rule
        ? { ...this.data.rule, onPipeline: this.data.rule.onPipeline ?? '' }
        : undefined;

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

    save(): void {
        if (!this.schemaForm.validate()) return;
        const v = this.schemaForm.value() as Partial<AlertRule>;
        const onPipeline = String(v.onPipeline ?? '').trim();
        const body: AlertRuleUpsert = {
            name: this.isEdit ? this.data.rule!.name : String(v.name ?? '').trim(),
            metric: String(v.metric ?? '').trim(),
            comparator: String(v.comparator ?? 'gt'),
            threshold: Number(v.threshold ?? 0),
            window: String(v.window ?? '15m'),
            severity: String(v.severity ?? 'WARNING'),
            ...(onPipeline ? { onPipeline } : {}),
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
