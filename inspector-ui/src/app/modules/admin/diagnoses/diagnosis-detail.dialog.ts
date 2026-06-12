import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ToastrService } from 'ngx-toastr';
import { Diagnosis } from 'app/ucc/api';
import { AssistPanelComponent } from 'app/ucc/components/assist-panel.component';

/** Detail view for one diagnosis — replaces inspector-ui's dx-popup drawer. */
@Component({
    selector: 'app-diagnosis-detail-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatIconModule, AssistPanelComponent],
    template: `
        <h2 mat-dialog-title>Diagnosis</h2>
        <mat-dialog-content>
            <table class="mb-4 text-sm">
                <tbody>
                    <tr><th class="pr-4 text-left">Pipeline</th><td>{{ d.pipeline }}</td></tr>
                    <tr><th class="pr-4 text-left">Batch</th><td>{{ d.batchId }}</td></tr>
                    <tr><th class="pr-4 text-left">Severity</th><td>{{ d.severity }}</td></tr>
                    <tr><th class="pr-4 text-left">Heuristic only</th><td>{{ d.heuristicOnly }}</td></tr>
                </tbody>
            </table>

            <div class="font-semibold">Root cause</div>
            <p class="mt-1 whitespace-pre-wrap">{{ d.rootCause }}</p>

            @if (d.citations?.length) {
                <div class="mt-3 flex flex-wrap gap-2">
                    @for (c of d.citations; track c) {
                        <span class="rounded-full bg-gray-200 px-3 py-0.5 text-xs dark:bg-gray-700">
                            {{ c.source }}: {{ c.ref }}
                        </span>
                    }
                </div>
            }

            @if (d.suggestedAlertRuleToon) {
                <div class="mt-4">
                    <div class="flex items-center justify-between font-semibold">
                        Suggested alert rule (.toon)
                        <button mat-icon-button (click)="copy(d.suggestedAlertRuleToon)">
                            <mat-icon svgIcon="heroicons_outline:clipboard-document"></mat-icon>
                        </button>
                    </div>
                    <pre class="mt-1 overflow-auto rounded bg-gray-100 p-3 text-xs dark:bg-gray-800">{{ d.suggestedAlertRuleToon }}</pre>
                </div>
            }

            <div class="mt-4 font-semibold">Refine as an alert rule</div>
            <p class="text-secondary mb-2 text-sm">
                Hand this diagnosis to the diagnose-and-alert skill to produce a validated draft rule.
            </p>
            <app-assist-panel
                intent="diagnose-and-alert"
                [userText]="alertText"
                placeholder="adjust the alert description…"
            ></app-assist-panel>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class DiagnosisDetailDialog {
    readonly d = inject<Diagnosis>(MAT_DIALOG_DATA);
    private toastr = inject(ToastrService);

    /** Seed for the diagnose-and-alert assist flow, like inspector-ui's detail drawer. */
    readonly alertText = `${this.d.rootCause} (pipeline ${this.d.pipeline}, batch ${this.d.batchId})`;

    copy(text?: string | null): void {
        if (!text) return;
        navigator.clipboard?.writeText(text).then(
            () => this.toastr.success('Copied to clipboard'),
            () => this.toastr.warning('Clipboard unavailable'),
        );
    }
}
