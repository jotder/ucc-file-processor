import { Component, inject, Injectable } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';

/** Resolved data passed to the dialog (the service fills defaults). */
interface ConfirmData {
    title: string;
    message: string;
    confirmText: string;
    cancelText: string;
    destructive: boolean;
    /** When set, the user must type this exact text to enable the confirm button. */
    requireText?: string;
}

@Component({
    selector: 'inspecto-confirm-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, FormsModule],
    template: `
        <h2 mat-dialog-title>{{ data.title }}</h2>
        <mat-dialog-content>
            <div class="whitespace-pre-wrap">{{ data.message }}</div>
            @if (data.requireText) {
                <mat-form-field class="mt-4 w-full" subscriptSizing="dynamic">
                    <mat-label>Type “{{ data.requireText }}” to confirm</mat-label>
                    <input matInput [(ngModel)]="typed" autocomplete="off" />
                </mat-form-field>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="false">{{ data.cancelText }}</button>
            <button
                mat-flat-button
                [color]="data.destructive ? 'warn' : 'primary'"
                [disabled]="data.requireText ? typed !== data.requireText : false"
                [mat-dialog-close]="true"
            >
                {{ data.confirmText }}
            </button>
        </mat-dialog-actions>
    `,
})
export class InspectoConfirmDialog {
    readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
    typed = '';
}

/** Options for {@link InspectoConfirmService.confirmDestructive}. */
export interface ConfirmOptions {
    title?: string;
    confirmText?: string;
    cancelText?: string;
    destructive?: boolean;
    /** Require the user to type this exact text (e.g. the resource id) before the confirm enables. */
    requireText?: string;
}

/** Promise-based confirm — replaces DevExtreme's `confirm()` for the ported screens. */
@Injectable({ providedIn: 'root' })
export class InspectoConfirmService {
    private dialog = inject(MatDialog);

    /** Neutral confirm (primary button). Backward-compatible signature. */
    async confirm(message: string, title = 'Confirm'): Promise<boolean> {
        return this.ask(message, { title });
    }

    /**
     * Destructive confirm — a red ("warn") confirm button, defaulting the title/label to "Delete".
     * Pass {@link ConfirmOptions.requireText} to require typed confirmation for high-risk deletes.
     */
    async confirmDestructive(message: string, opts: ConfirmOptions = {}): Promise<boolean> {
        return this.ask(message, { destructive: true, title: 'Delete', confirmText: 'Delete', ...opts });
    }

    private async ask(message: string, opts: ConfirmOptions): Promise<boolean> {
        const data: ConfirmData = {
            title: opts.title ?? 'Confirm',
            message,
            confirmText: opts.confirmText ?? 'OK',
            cancelText: opts.cancelText ?? 'Cancel',
            destructive: opts.destructive ?? false,
            requireText: opts.requireText,
        };
        const ref = this.dialog.open(InspectoConfirmDialog, { data, width: '420px' });
        return !!(await firstValueFrom(ref.afterClosed()));
    }
}
