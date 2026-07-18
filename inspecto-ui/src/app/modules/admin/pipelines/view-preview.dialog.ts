import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ColDef } from 'ag-grid-community';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PipelineViewData, ViewsService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { DataTableComponent } from 'app/inspecto/data-table';

/** Dialog data: the `sink.view` node's store name (its `derived_sql` runs against this). */
export interface ViewPreviewData {
    viewName: string;
}

/**
 * Preview a `sink.view` node's data (T32 UI-consumer follow-up): fetches bounded rows via
 * `GET /views/{name}/data`, the resource-capped read of the view's captured `derived_sql`.
 * A view is non-persistent — there is nothing to browse via the Data Browser's table list — so this
 * is the only place a `sink.view` node's output is inspectable in the UI.
 */
@Component({
    selector: 'app-view-preview-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatProgressSpinnerModule, InspectoAlertComponent, DataTableComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>View · {{ data.viewName }}</h2>
        <mat-dialog-content class="!max-h-[70vh]">
            @if (loading()) {
                <div class="flex items-center gap-2 text-sm">
                    <mat-spinner diameter="16"></mat-spinner> Loading…
                </div>
            } @else if (error()) {
                <inspecto-alert variant="error" title="Preview unavailable">{{ error() }}</inspecto-alert>
            } @else if (result(); as r) {
                @if (r.capped) {
                    <inspecto-alert class="mb-2 block" variant="info" icon="heroicons_outline:information-circle">
                        Showing {{ r.rowCount.toLocaleString() }} row(s) — the view has more; this preview is capped.
                    </inspecto-alert>
                }
                <inspecto-data-table
                    tier="mini"
                    [rows]="r.rows"
                    [columns]="columnsFor(r)"
                    [autoHeight]="true"
                    noRowsTitle="No rows"
                    noRowsHint="This view's derived_sql returned nothing." />
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class ViewPreviewDialog implements OnInit {
    private api = inject(ViewsService);
    private ref = inject(MatDialogRef<ViewPreviewDialog>);
    readonly data = inject<ViewPreviewData>(MAT_DIALOG_DATA);

    readonly loading = signal(true);
    readonly result = signal<PipelineViewData | null>(null);
    readonly error = signal<string | null>(null);

    ngOnInit(): void {
        this.api.data(this.data.viewName).subscribe({
            next: (r) => {
                this.loading.set(false);
                this.result.set(r);
            },
            error: (e) => {
                this.loading.set(false);
                this.error.set(apiErrorMessage(e, 'Preview failed'));
            },
        });
    }

    columnsFor(r: PipelineViewData): ColDef[] {
        return r.columns.map((c) => ({ field: c, headerName: c }));
    }
}
