import { Component, inject, Injectable } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';

@Component({
    selector: 'app-ucc-confirm-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule],
    template: `
        <h2 mat-dialog-title>{{ data.title }}</h2>
        <mat-dialog-content>{{ data.message }}</mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="false">Cancel</button>
            <button mat-flat-button color="primary" [mat-dialog-close]="true">OK</button>
        </mat-dialog-actions>
    `,
})
export class UccConfirmDialog {
    readonly data = inject<{ title: string; message: string }>(MAT_DIALOG_DATA);
}

/** Promise-based confirm — replaces DevExtreme's `confirm()` for the ported screens. */
@Injectable({ providedIn: 'root' })
export class UccConfirmService {
    private dialog = inject(MatDialog);

    async confirm(message: string, title = 'Confirm'): Promise<boolean> {
        const ref = this.dialog.open(UccConfirmDialog, { data: { title, message }, width: '400px' });
        return !!(await firstValueFrom(ref.afterClosed()));
    }
}
