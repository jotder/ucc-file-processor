import { AfterViewInit, ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, ValidatorFn } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, Expectation, ExpectationKind, ExpectationsService, ExpectationUpsert } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { EXPECTATION_ATTRIBUTES } from './expectation-attributes';

/** Dialog input: an existing expectation ⇒ edit; absent ⇒ create. */
export interface ExpectationFormData {
    expectation?: Expectation;
    /** Ids already in use — on create the name control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
}
export interface ExpectationFormResult {
    saved?: Expectation;
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Create / edit an Expectation (C2) — fully SchemaForm-driven ({@link EXPECTATION_ATTRIBUTES}); the
 * kind-specific parameters (range bounds / regex / reference) appear via `dependsOn`. The id is
 * immutable on edit (it is the storage key).
 */
@Component({
    selector: 'app-expectation-form-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, InspectoAlertComponent, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ isEdit ? 'Edit expectation "' + data.expectation!.name + '"' : 'New expectation' }}</h2>
        <mat-dialog-content>
            @if (writesDisabled()) {
                <inspecto-alert variant="warning" title="Writes are disabled">
                    The server is running read-only — the expectation was not saved.
                </inspecto-alert>
            }
            <inspecto-schema-form [specs]="attributes" [initial]="initialValue"></inspecto-schema-form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close [disabled]="saving()">Cancel</button>
            <button type="button" mat-flat-button color="primary" [disabled]="saving()" (click)="save()">
                {{ isEdit ? 'Save' : 'Create' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class ExpectationFormDialog implements AfterViewInit {
    private api = inject(ExpectationsService);
    private ref = inject(MatDialogRef<ExpectationFormDialog, ExpectationFormResult>);
    private toastr = inject(ToastrService);
    readonly data = inject<ExpectationFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly isEdit = !!this.data.expectation;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly attributes = EXPECTATION_ATTRIBUTES;

    readonly initialValue: Record<string, unknown> | undefined = this.data.expectation
        ? {
              name: this.data.expectation.name,
              description: this.data.expectation.description ?? '',
              targetType: this.data.expectation.targetType,
              target: this.data.expectation.target,
              column: this.data.expectation.column,
              kind: this.data.expectation.kind,
              min: this.data.expectation.min ?? undefined,
              max: this.data.expectation.max ?? undefined,
              pattern: this.data.expectation.pattern ?? '',
              refDataset: this.data.expectation.refDataset ?? '',
              refColumn: this.data.expectation.refColumn ?? '',
              severity: this.data.expectation.severity,
              enabled: this.data.expectation.enabled,
          }
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
        const v = this.schemaForm.value() as {
            name?: string;
            description?: string;
            targetType: 'pipeline' | 'job';
            target: string;
            column: string;
            kind: ExpectationKind;
            min?: number;
            max?: number;
            pattern?: string;
            refDataset?: string;
            refColumn?: string;
            severity?: string;
            enabled?: boolean;
        };
        const body: ExpectationUpsert = {
            name: this.isEdit ? this.data.expectation!.name : String(v.name ?? '').trim(),
            description: String(v.description ?? '').trim(),
            targetType: v.targetType,
            target: String(v.target ?? '').trim(),
            column: String(v.column ?? '').trim(),
            kind: v.kind,
            min: v.kind === 'range' ? (v.min ?? null) : null,
            max: v.kind === 'range' ? (v.max ?? null) : null,
            pattern: v.kind === 'regex' ? String(v.pattern ?? '').trim() : null,
            refDataset: v.kind === 'referential' ? String(v.refDataset ?? '').trim() : null,
            refColumn: v.kind === 'referential' ? String(v.refColumn ?? '').trim() : null,
            severity: v.severity ?? 'MAJOR',
            enabled: v.enabled !== false,
        };
        this.saving.set(true);
        const call = this.isEdit ? this.api.update(body.name, body) : this.api.create(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else this.toastr.error(apiErrorMessage(e, 'Could not save the expectation.'));
            },
        });
    }
}
