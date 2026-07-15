import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { LensService } from 'app/inspecto/api';
import { Requirement } from 'app/inspecto/requirement';

export type RequirementDecisionResult =
    | { action: 'decide'; accept: boolean; note?: string }
    | { action: 'deliver'; note?: string };

/**
 * Requirement detail — full description + the Builder-queue triage actions (Wave-3 interview decision,
 * 2026-07-03): Accept/Reject a `submitted` requirement, or Deliver an `accepted` one. Business (and
 * every other read-only lens) sees the same detail read-only — no decision inputs render.
 */
@Component({
    selector: 'app-requirement-decision-dialog',
    standalone: true,
    imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, StatusBadgeComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>
            {{ data.title }} <inspecto-status-badge class="ml-2 align-middle" [value]="data.status" />
        </h2>
        <mat-dialog-content class="flex flex-col gap-3">
            <div class="text-secondary text-xs font-semibold uppercase tracking-wider">{{ data.kind }}</div>
            <p class="whitespace-pre-wrap text-sm">{{ data.description }}</p>
            @if (data.decisionNote) {
                <p class="text-secondary text-sm"><span class="font-medium">Decision note:</span> {{ data.decisionNote }}</p>
            }
            @if (data.deliveredNote) {
                <p class="text-secondary text-sm"><span class="font-medium">Delivered via:</span> {{ data.deliveredNote }}</p>
            }

            @if (lens.canTriageRequirements() && data.status === 'submitted') {
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Note (optional)</mat-label>
                    <textarea matInput rows="2" [(ngModel)]="noteValue" cdkFocusInitial></textarea>
                </mat-form-field>
                <div class="flex gap-2">
                    <button mat-flat-button color="primary" (click)="decide(true)">Accept</button>
                    <button mat-stroked-button color="warn" (click)="decide(false)">Reject</button>
                </div>
            }
            @if (lens.canTriageRequirements() && data.status === 'accepted') {
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Delivered via (optional)</mat-label>
                    <input matInput [(ngModel)]="noteValue" placeholder="e.g. dashboard/churn_kpi" cdkFocusInitial />
                </mat-form-field>
                <button mat-flat-button color="primary" (click)="deliver()">Mark delivered</button>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class RequirementDecisionDialog {
    private ref = inject(MatDialogRef<RequirementDecisionDialog, RequirementDecisionResult>);
    readonly data = inject<Requirement>(MAT_DIALOG_DATA);
    readonly lens = inject(LensService);

    noteValue = '';

    decide(accept: boolean): void {
        this.ref.close({ action: 'decide', accept, note: this.noteValue || undefined });
    }

    deliver(): void {
        this.ref.close({ action: 'deliver', note: this.noteValue || undefined });
    }
}
