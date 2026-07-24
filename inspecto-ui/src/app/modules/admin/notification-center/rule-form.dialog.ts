import { AfterViewInit, ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, ValidatorFn } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, NotificationRule, NotificationsService } from 'app/inspecto/api';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { RULE_ATTRIBUTES } from './rule-attributes';

/** Dialog input: an existing rule ⇒ edit; absent ⇒ create. */
export interface RuleFormData {
    rule?: NotificationRule;
    /** Ids already in use — on create the id control rejects a duplicate inline (product-wide rule). */
    existingIds?: string[];
}
export interface RuleFormResult {
    saved?: NotificationRule;
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Create / edit an authored notification rule — fully SchemaForm-driven ({@link RULE_ATTRIBUTES});
 * mirrors {@link ChannelFormDialog}. An authored rule is checked ahead of the built-in defaults,
 * so it can override a built-in's copy/routing or cover a new event type.
 */
@Component({
    selector: 'app-rule-form-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ isEdit ? 'Edit rule' : 'New rule' }}</h2>
        <mat-dialog-content class="pt-2" style="min-width: 30rem">
            <inspecto-schema-form [specs]="attributes" [initial]="initialValue" (submitted)="save()" />
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving()" (click)="save()">
                {{ isEdit ? 'Save' : 'Create' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class RuleFormDialog implements AfterViewInit {
    private api = inject(NotificationsService);
    private ref = inject(MatDialogRef<RuleFormDialog, RuleFormResult>);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    readonly data = inject<RuleFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding a dirty form. */
    readonly requestClose = guardDirtyClose(this.ref, () => this.schemaForm?.isDirty() ?? false, this.confirm);

    readonly isEdit = !!this.data.rule;
    readonly saving = signal(false);
    readonly attributes = RULE_ATTRIBUTES;

    readonly initialValue: Record<string, unknown> | undefined = this.data.rule
        ? {
              id: this.data.rule.id,
              eventType: this.data.rule.eventType,
              minLevel: this.data.rule.minLevel ?? '',
              category: this.data.rule.category,
              titleTemplate: this.data.rule.titleTemplate ?? '',
              bodyTemplate: this.data.rule.bodyTemplate ?? '',
              dedupeKeyTemplate: this.data.rule.dedupeKeyTemplate ?? '',
              enabled: this.data.rule.enabled,
          }
        : undefined;

    ngAfterViewInit(): void {
        const idCtrl = this.schemaForm.form.get('id');
        if (this.isEdit) {
            // The id is immutable once created (it is the storage key).
            idCtrl?.disable({ emitEvent: false });
        } else if (this.data.existingIds?.length) {
            // Block a duplicate id inline rather than relying on the server 409 (product-wide form rule).
            idCtrl?.addValidators(uniqueNameValidator(this.data.existingIds));
            idCtrl?.updateValueAndValidity({ emitEvent: false });
        }
    }

    save(): void {
        if (!this.schemaForm.validate()) return;
        const v = this.schemaForm.value() as Partial<NotificationRule> & { minLevel?: string };
        const body: NotificationRule = {
            id: this.isEdit ? this.data.rule!.id : String(v.id ?? '').trim(),
            eventType: String(v.eventType ?? '').trim(),
            minLevel: v.minLevel ? v.minLevel : null,
            category: String(v.category ?? '').trim(),
            titleTemplate: v.titleTemplate?.trim() || undefined,
            bodyTemplate: v.bodyTemplate?.trim() || undefined,
            dedupeKeyTemplate: v.dedupeKeyTemplate?.trim() || undefined,
            enabled: v.enabled !== false,
        };
        this.saving.set(true);
        // The server's PUT is a full replace (id bound from the path) — always send the whole rule.
        const call = this.isEdit ? this.api.updateRule(body.id, body) : this.api.createRule(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the rule.'));
            },
        });
    }
}
