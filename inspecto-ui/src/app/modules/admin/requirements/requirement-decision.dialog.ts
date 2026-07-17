import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { componentRefOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { LensService } from 'app/inspecto/api';
import { AttributeOption } from 'app/inspecto/component-model';
import { Requirement } from 'app/inspecto/requirement';

export type RequirementDecisionResult =
    | { action: 'decide'; accept: boolean; note?: string }
    | { action: 'deliver'; note?: string };

/**
 * Requirement detail — full description + the Builder-queue triage actions (Wave-3 interview decision,
 * 2026-07-03): Accept/Reject a `submitted` requirement, or Deliver an `accepted` one. Business (and
 * every other read-only lens) sees the same detail read-only — no decision inputs render.
 *
 * "Delivered via" is a cross-kind component picker (C1 follow-up): suggestions are the app's
 * components in `<kind>/<id>` form — picking one makes the note a real Registry `delivered-by`
 * edge (`requirementRefs`); free text stays valid (suggestions assist, they never constrain).
 */
@Component({
    selector: 'app-requirement-decision-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatAutocompleteModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, StatusBadgeComponent],
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
                    <textarea matInput rows="2" [formControl]="note" cdkFocusInitial></textarea>
                </mat-form-field>
                <div class="flex gap-2">
                    <button mat-flat-button color="primary" (click)="decide(true)">Accept</button>
                    <button mat-stroked-button color="warn" (click)="decide(false)">Reject</button>
                </div>
            }
            @if (lens.canTriageRequirements() && data.status === 'accepted') {
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Delivered via (optional)</mat-label>
                    <input matInput [formControl]="note" [matAutocomplete]="ac"
                           placeholder="e.g. dashboard/churn_kpi" cdkFocusInitial
                           (focus)="loadOptions()" />
                    <mat-autocomplete #ac="matAutocomplete">
                        @for (opt of filteredOptions(); track opt.value) {
                            <mat-option [value]="opt.value">{{ opt.label }}</mat-option>
                        }
                    </mat-autocomplete>
                    <mat-hint>Pick a component to link it in the Registry graph — free text is fine too</mat-hint>
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

    readonly note = new FormControl('', { nonNullable: true });

    /** Cross-kind `<kind>/<id>` suggestions, loaded once on first focus (best-effort). */
    private readonly loadComponentRefs = componentRefOptionLoader();
    private readonly options = signal<AttributeOption[]>([]);
    private readonly noteText = signal('');
    private loaded = false;

    constructor() {
        this.note.valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => this.noteText.set(v));
    }

    loadOptions(): void {
        if (this.loaded) return;
        this.loaded = true;
        Promise.resolve(this.loadComponentRefs({})).then(
            (opts) => this.options.set(opts),
            () => undefined,
        );
    }

    /** Suggestions narrowed by the typed text (value match, case-insensitive). */
    filteredOptions(): AttributeOption[] {
        const q = this.noteText().trim().toLowerCase();
        const all = this.options();
        return q ? all.filter((o) => o.value.toLowerCase().includes(q)) : all;
    }

    decide(accept: boolean): void {
        this.ref.close({ action: 'decide', accept, note: this.note.value || undefined });
    }

    deliver(): void {
        this.ref.close({ action: 'deliver', note: this.note.value || undefined });
    }
}
