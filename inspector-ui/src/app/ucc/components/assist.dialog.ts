import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { AssistPanelComponent } from './assist-panel.component';

/** Hosts the AssistPanel in a dialog (e.g. jobs' "New schedule" nl-to-schedule flow). */
@Component({
    selector: 'app-assist-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, AssistPanelComponent],
    template: `
        <h2 mat-dialog-title>{{ data.title }}</h2>
        <mat-dialog-content>
            <app-assist-panel
                [intent]="data.intent"
                [placeholder]="data.placeholder || 'Describe what you need…'"
                [userText]="data.userText || ''"
            ></app-assist-panel>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class AssistDialog {
    readonly data = inject<{ title: string; intent: string; placeholder?: string; userText?: string }>(MAT_DIALOG_DATA);
}
