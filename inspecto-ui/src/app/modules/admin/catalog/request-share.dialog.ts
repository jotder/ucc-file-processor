import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ExchangeOffer } from 'app/inspecto/api';

export interface RequestShareData {
    offer: ExchangeOffer;
    consumer: string;
}

/** What the consumer asked for — the dialog's result, ready for `ExchangeService.request`. */
export interface RequestShareResult {
    purpose: string;
    mode: 'snapshot' | 'live';
}

/**
 * "Request access" — the consumer side of the Exchange handshake. Asks only what the request needs
 * (an optional purpose the owner sees when approving, and the delivery mode); owner/item/consumer
 * all travel in the dialog data.
 */
@Component({
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Request access</h2>
        <mat-dialog-content class="flex w-96 max-w-full flex-col gap-4">
            <div class="text-secondary text-sm">
                {{ data.offer.kind }} <strong>{{ data.offer.owner }}/{{ data.offer.item }}</strong> — the
                owner space reviews and approves your request before anything is shared.
            </div>
            <form [formGroup]="form" class="flex flex-col gap-2" (ngSubmit)="submit()">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Purpose (optional)</mat-label>
                    <textarea
                        matInput
                        formControlName="purpose"
                        rows="3"
                        placeholder="Why your space needs this data — shown to the owner"
                    ></textarea>
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Delivery mode</mat-label>
                    <mat-select formControlName="mode">
                        <mat-option value="snapshot">Snapshot — versioned copies the owner refreshes</mat-option>
                        <mat-option value="live">Live — read the owner's current data</mat-option>
                    </mat-select>
                </mat-form-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Cancel</button>
            <button mat-flat-button color="primary" (click)="submit()">Request</button>
        </mat-dialog-actions>
    `,
})
export class RequestShareDialog {
    readonly data = inject<RequestShareData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<RequestShareDialog>);

    readonly form = inject(FormBuilder).nonNullable.group({
        purpose: [''],
        mode: ['snapshot' as 'snapshot' | 'live'],
    });

    submit(): void {
        this.ref.close(this.form.getRawValue() satisfies RequestShareResult);
    }
}
