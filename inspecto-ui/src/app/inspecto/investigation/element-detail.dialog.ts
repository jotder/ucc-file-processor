import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { PivotService, PivotView } from './pivot.service';

/** One label/value line of the detail sheet. */
export interface ElementDetailRow {
    label: string;
    value: string;
}

/** Reference to the underlying Incident/Case a clicked element represents, when known (ui-design-review
 *  R8 — investigation pivots). Only set when the host can identify the record (e.g. a geo point whose
 *  row carries an `objectId`/`objectType` column) — an entity graph's synthetic node ids are NOT a
 *  record reference and must not set this. */
export interface ElementObjectRef {
    id: string;
    type: 'INCIDENT' | 'CASE';
}

/** Payload for the node/edge detail popup (canvas click → full details). */
export interface ElementDetailData {
    title: string;
    /** The element's kind, shown under the title. */
    subtitle: string;
    rows: ElementDetailRow[];
    /** Offered branch action for nodes with downstream links; absent = hidden. */
    branch?: 'collapse' | 'expand';
    /** The Incident/Case this element represents, if the host could resolve one — offers "Open record". */
    objectRef?: ElementObjectRef;
    /**
     * Phase E incremental expand: the host's GraphSource can fetch this node's one-hop neighborhood
     * and merge it in — offers "Fetch neighbors". Distinct from `branch` (which only shows/hides
     * already-loaded descendants); this one re-queries for new data.
     */
    expandable?: boolean;
    /** Alternate views this element's selection can be pivoted into (ui-design-review R8 — investigation
     *  pivots), excluding the host's own current view. Absent/empty = hidden; only offered when
     *  `objectRef` is set, since the pivot travels the record reference. */
    pivotViews?: PivotView[];
}

/** What the caller should do next; closing without a choice does nothing. Pivoting to another view
 *  (ui-design-review R8) is handled inside this dialog via `PivotService` and does not surface here. */
export type ElementDetailResult = 'focus' | 'collapse' | 'expand' | 'open-record' | 'expand-neighbors' | undefined;

const PIVOT_LABEL: Record<PivotView, string> = { graph: 'View in graph', map: 'View on map' };
const PIVOT_ICON: Record<PivotView, string> = { graph: 'heroicons_outline:share', map: 'heroicons_outline:map' };

/**
 * **Element detail popup** (investigation studios — Link Analysis, Geo Map Analysis): full details
 * of a clicked element (node/link/point/route), plus focus-on-canvas and, for graphs,
 * collapse/expand-branch actions. Pure presentational — the pane owns the data.
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
            @if (data.expandable) {
                <button mat-button (click)="close('expand-neighbors')">
                    <mat-icon svgIcon="heroicons_outline:arrow-path"></mat-icon>
                    Fetch neighbors
                </button>
            }
            <button mat-button (click)="close('focus')">
                <mat-icon svgIcon="heroicons_outline:viewfinder-circle"></mat-icon>
                Focus
            </button>
            @if (data.objectRef; as ref) {
                <button mat-button (click)="close('open-record')">
                    <mat-icon svgIcon="heroicons_outline:arrow-top-right-on-square"></mat-icon>
                    Open {{ ref.type === 'CASE' ? 'case' : 'record' }}
                </button>
                @for (view of data.pivotViews ?? []; track view) {
                    <button mat-button (click)="pivot(view)">
                        <mat-icon [svgIcon]="pivotIcon(view)"></mat-icon>
                        {{ pivotLabel(view) }}
                    </button>
                }
            }
            <button mat-flat-button color="primary" (click)="close(undefined)">Close</button>
        </mat-dialog-actions>
    `,
})
export class ElementDetailDialog {
    readonly data: ElementDetailData = inject(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<ElementDetailDialog>);
    private pivotService = inject(PivotService);

    close(result: ElementDetailResult): void {
        this.ref.close(result);
    }

    pivotLabel(view: PivotView): string {
        return PIVOT_LABEL[view];
    }

    pivotIcon(view: PivotView): string {
        return PIVOT_ICON[view];
    }

    /** Hand off to the target view and close — the pivot itself is a navigation, not a dialog result. */
    pivot(view: PivotView): void {
        const ref = this.data.objectRef;
        if (!ref) return;
        this.pivotService.pivotTo(view, ref);
        this.ref.close(undefined);
    }
}
