import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ExchangeGrant } from 'app/inspecto/api';

export interface GrantGovernanceData {
    field: 'pin' | 'expiry';
    grant: ExchangeGrant;
}

/**
 * The result: the new value, or `null` to clear. For `pin` it's a snapshot version string; for
 * `expiry` it's epoch millis. `undefined` (dialog dismissed) means no change.
 */
export type GrantGovernanceResult = string | number | null;

/**
 * A compact grant-governance editor (S3) — sets or clears one optional field on a grant: a consumer's
 * snapshot version **pin**, or an owner's **expiry**. Rendered as a version text box or a date input by
 * {@link GrantGovernanceData.field}; the "Clear" action closes with `null` to remove the value.
 */
@Component({
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ isPin ? 'Pin snapshot version' : 'Set grant expiry' }}</h2>
        <mat-dialog-content class="flex w-80 max-w-full flex-col gap-3">
            <div class="text-secondary text-sm">
                {{ data.grant.kind }} <strong>{{ data.grant.owner }}/{{ data.grant.item }}</strong>
                @if (isPin) {
                    — pin the consumer to a fixed snapshot version, or clear to track the latest.
                } @else {
                    — the grant is automatically revoked after this time; clear for no expiry.
                }
            </div>
            <form [formGroup]="form">
                @if (isPin) {
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Version</mat-label>
                        <input matInput formControlName="pin" placeholder="e.g. v3" />
                    </mat-form-field>
                } @else {
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Expires at</mat-label>
                        <input matInput type="datetime-local" formControlName="expiry" />
                    </mat-form-field>
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Cancel</button>
            <button mat-button (click)="clear()">Clear</button>
            <button mat-flat-button color="primary" (click)="submit()">Save</button>
        </mat-dialog-actions>
    `,
})
export class GrantGovernanceDialog {
    readonly data = inject<GrantGovernanceData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<GrantGovernanceDialog>);

    readonly isPin = this.data.field === 'pin';

    readonly form = inject(FormBuilder).nonNullable.group({
        pin: [this.data.grant.pin ?? ''],
        expiry: [this.toLocalInput(this.data.grant.expiresAt)],
    });

    submit(): void {
        if (this.isPin) {
            const v = this.form.controls.pin.value.trim();
            this.ref.close(v ? v : null);
        } else {
            const v = this.form.controls.expiry.value;
            this.ref.close(v ? new Date(v).getTime() : null);
        }
    }

    clear(): void {
        this.ref.close(null);
    }

    /** Epoch millis → the `datetime-local` control's local `YYYY-MM-DDTHH:mm` string (empty when unset). */
    private toLocalInput(epoch: number | null): string {
        if (!epoch) return '';
        const d = new Date(epoch);
        const pad = (n: number): string => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }
}
