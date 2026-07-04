import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

/** One label/value line of the detail sheet. */
export interface ElementDetailRow {
    label: string;
    value: string;
}

/** Payload for the node/edge detail popup (canvas click → full details). */
export interface ElementDetailData {
    title: string;
    /** The element's kind, shown under the title. */
    subtitle: string;
    rows: ElementDetailRow[];
    /** Offered branch action for nodes with downstream links; absent = hidden. */
    branch?: 'collapse' | 'expand';
}

/** What the caller should do next; closing without a choice does nothing. */
export type ElementDetailResult = 'focus' | 'collapse' | 'expand' | undefined;

/**
 * **Element detail popup** (Link Analysis): full details of a clicked node or link, plus
 * focus-on-canvas and collapse/expand-branch actions. Pure presentational — the pane owns the data.
 */
@Component({
    selector: 'inspecto-element-detail-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatDialogModule, MatIconModule],
    template: `
        <h2 mat-dialog-title class="truncate">{{ data.title }}</h2>
        <mat-dialog-content>
            <p class="text-secondary -mt-2 text-sm">{{ data.subtitle }}</p>
            <dl class="mt-3 flex flex-col gap-1 text-sm">
                @for (row of data.rows; track row.label) {
                    <div class="flex items-baseline gap-3">
                        <dt class="text-secondary w-28 shrink-0">{{ row.label }}</dt>
                        <dd class="min-w-0 break-words font-medium">{{ row.value }}</dd>
                    </div>
                }
            </dl>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (data.branch === 'collapse') {
                <button mat-button (click)="close('collapse')">
                    <mat-icon svgIcon="heroicons_outline:minus-circle"></mat-icon>
                    Collapse branch
                </button>
            } @else if (data.branch === 'expand') {
                <button mat-button (click)="close('expand')">
                    <mat-icon svgIcon="heroicons_outline:plus-circle"></mat-icon>
                    Expand branch
                </button>
            }
            <button mat-button (click)="close('focus')">
                <mat-icon svgIcon="heroicons_outline:viewfinder-circle"></mat-icon>
                Focus
            </button>
            <button mat-flat-button color="primary" (click)="close(undefined)">Close</button>
        </mat-dialog-actions>
    `,
})
export class ElementDetailDialog {
    readonly data: ElementDetailData = inject(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<ElementDetailDialog>);

    close(result: ElementDetailResult): void {
        this.ref.close(result);
    }
}
