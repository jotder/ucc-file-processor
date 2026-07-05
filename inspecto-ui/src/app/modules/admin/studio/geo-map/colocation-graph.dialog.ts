import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { G6GraphData } from 'app/inspecto/graph';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';

/**
 * **Co-location graph popup** — the geo → link-analysis bridge: entities that met render as an
 * Entity/Link graph through the same shared G6 host the Link Analysis studio uses. (A full
 * hand-off into `/studio/link-analysis` needs its dataset-backed source model — the V1
 * multi-mapping line; see docs/superpower/geo-map-analysis-plan.md §Phase 3.)
 */
@Component({
    selector: 'inspecto-colocation-graph-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatDialogModule, GraphViewComponent],
    template: `
        <h2 mat-dialog-title>Who met whom</h2>
        <mat-dialog-content>
            <p class="text-secondary -mt-2 text-sm">
                Entities linked when they were repeatedly co-located — same renderer as Link Analysis.
            </p>
            <div class="h-96 w-[42rem] max-w-full">
                <inspecto-graph-view [data]="data.graph" [fill]="true" [tooltips]="true" />
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-flat-button color="primary" mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class ColocationGraphDialog {
    readonly data: { graph: G6GraphData } = inject(MAT_DIALOG_DATA);
}
