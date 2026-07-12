import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { currentOperator, objectTags } from './mail-model';

/**
 * <b>Merge</b> Cases (C2, GLOSSARY §9): pick the surviving case among the selection; the others are
 * absorbed into it (members re-point, tags/watchers union, sources close with a MERGED_INTO trace).
 * Closes with `true` after a successful merge.
 */
@Component({
    selector: 'app-merge-cases-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, MatRadioModule],
    template: `
        <h2 mat-dialog-title>Merge {{ data.cases.length }} cases</h2>
        <mat-dialog-content class="flex flex-col gap-2 pt-2">
            <p class="text-secondary text-sm">
                Pick the <b>surviving</b> case — the others are absorbed into it (their member incidents,
                tags and watchers move over; the absorbed cases close with a merge trace).
            </p>
            <mat-radio-group class="flex flex-col gap-1" [value]="survivorId()" (change)="survivorId.set($event.value)"
                             aria-label="Surviving case">
                @for (c of data.cases; track c.id) {
                    <mat-radio-button [value]="c.id">
                        {{ c.title }}
                        <span class="text-secondary text-xs">({{ c.id }} · {{ tagsOf(c) || 'no tags' }})</span>
                    </mat-radio-button>
                }
            </mat-radio-group>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="false">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving()" (click)="merge()">Merge</button>
        </mat-dialog-actions>
    `,
})
export class MergeCasesDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<MergeCasesDialog>);
    private toastr = inject(ToastrService);
    readonly data = inject<{ cases: OperationalObject[] }>(MAT_DIALOG_DATA);

    /** Default survivor: the most recently updated case in the selection. */
    readonly survivorId = signal(
        [...this.data.cases].sort((a, b) => b.updatedAt - a.updatedAt)[0]?.id ?? '',
    );
    readonly saving = signal(false);

    tagsOf(c: OperationalObject): string {
        return objectTags(c).join(', ');
    }

    merge(): void {
        const survivor = this.survivorId();
        const sources = this.data.cases.map((c) => c.id).filter((id) => id !== survivor);
        if (!survivor || !sources.length) return;
        this.saving.set(true);
        this.api.mergeCases(survivor, sources, currentOperator()).subscribe({
            next: (res) => {
                this.toastr.success(
                    `Merged ${res.merged.length} case(s) into ${res.survivor.id} · ${res.membersMoved} member(s) moved`,
                );
                this.ref.close(true);
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Merge failed'));
            },
        });
    }
}
