import { AfterViewInit, ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, ValidatorFn } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, NotificationChannel, NotificationsService } from 'app/inspecto/api';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { CHANNEL_ATTRIBUTES } from './channel-attributes';

/** Dialog input: an existing channel ⇒ edit; absent ⇒ create. */
export interface ChannelFormData {
    channel?: NotificationChannel;
    /** Ids already in use — on create the id control rejects a duplicate inline (product-wide rule). */
    existingIds?: string[];
}
export interface ChannelFormResult {
    saved?: NotificationChannel;
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Create / edit a notification channel (C4) — fully SchemaForm-driven ({@link CHANNEL_ATTRIBUTES});
 * the kind toggles which target field (address vs URL) is visible via `dependsOn`.
 */
@Component({
    selector: 'app-channel-form-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ isEdit ? 'Edit channel' : 'New channel' }}</h2>
        <mat-dialog-content class="pt-2" style="min-width: 30rem">
            <inspecto-schema-form [specs]="attributes" [initial]="initialValue" />
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="null">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving()" (click)="save()">
                {{ isEdit ? 'Save' : 'Create' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class ChannelFormDialog implements AfterViewInit {
    private api = inject(NotificationsService);
    private ref = inject(MatDialogRef<ChannelFormDialog, ChannelFormResult>);
    private toastr = inject(ToastrService);
    readonly data = inject<ChannelFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly isEdit = !!this.data.channel;
    readonly saving = signal(false);
    readonly attributes = CHANNEL_ATTRIBUTES;

    readonly initialValue: Record<string, unknown> | undefined = this.data.channel
        ? {
              id: this.data.channel.id,
              kind: this.data.channel.kind,
              target: this.data.channel.kind === 'EMAIL' ? this.data.channel.target : '',
              targetUrl: this.data.channel.kind === 'WEBHOOK' ? this.data.channel.target : '',
              description: this.data.channel.description ?? '',
              enabled: this.data.channel.enabled,
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
        const v = this.schemaForm.value() as {
            id?: string;
            kind: string;
            target?: string;
            targetUrl?: string;
            description?: string;
            enabled?: boolean;
        };
        const body = {
            id: this.isEdit ? this.data.channel!.id : String(v.id ?? '').trim(),
            kind: v.kind,
            target: String((v.kind === 'WEBHOOK' ? v.targetUrl : v.target) ?? '').trim(),
            description: v.description?.trim() || undefined,
            enabled: v.enabled !== false,
        };
        this.saving.set(true);
        const call = this.isEdit ? this.api.updateChannel(body.id, body) : this.api.createChannel(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the channel.'));
            },
        });
    }
}
