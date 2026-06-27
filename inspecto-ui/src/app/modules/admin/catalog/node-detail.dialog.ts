import { Component, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef } from 'ag-grid-community';
import { CatalogService, MetadataNode, NodeDetail } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { AssistPanelComponent } from 'app/inspecto/components/assist-panel.component';

/**
 * Catalog node inspector: node facts + attrs, neighbour grid (click to walk the graph
 * in place — the dialog reloads and the assist panel re-keys), explain-entity assist.
 */
@Component({
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatProgressSpinnerModule, DataTableComponent, AssistPanelComponent],
    template: `
        <h2 mat-dialog-title>{{ detail?.node?.label || 'Node' }}</h2>
        <mat-dialog-content>
            @if (loading) {
                <div class="flex items-center gap-3 py-6">
                    <mat-progress-spinner diameter="24" mode="indeterminate"></mat-progress-spinner>
                    <span class="text-secondary">Loading node…</span>
                </div>
            }
            @if (detail && !loading) {
                <table class="text-sm">
                    <tr><th scope="row" class="pr-4 text-left align-top">Id</th><td>{{ detail.node.id }}</td></tr>
                    <tr><th scope="row" class="pr-4 text-left align-top">Kind</th><td>{{ detail.node.kind }}</td></tr>
                    @if (detail.node.description) {
                        <tr><th scope="row" class="pr-4 text-left align-top">Description</th><td>{{ detail.node.description?.text }}</td></tr>
                    }
                    @if (detail.node.overlay) {
                        <tr><th scope="row" class="pr-4 text-left align-top">Freshness</th><td>{{ detail.node.overlay?.freshness || '—' }}</td></tr>
                        <tr><th scope="row" class="pr-4 text-left align-top">Row count</th><td>{{ detail.node.overlay?.rowCount ?? '—' }}</td></tr>
                    }
                </table>

                <div class="mt-4 font-semibold">Attributes</div>
                <pre class="mt-1 max-h-40 overflow-auto rounded bg-gray-100 p-2 text-xs dark:bg-gray-800">{{ pretty(detail.node.attrs || {}) }}</pre>

                <div class="mt-4 font-semibold">
                    Neighbours ({{ detail.neighbors.nodes.length }} nodes, {{ detail.neighbors.edges.length }} edges)
                </div>
                <inspecto-data-table
                    tier="mini"
                    sourceName="neighbours"
                    [rows]="detail.neighbors.nodes"
                    [columns]="neighbourColumns"
                    height="12rem"
                    noRowsTitle="No neighbours"
                    (rowClick)="onNeighbourClicked($any($event))"
                />


                <div class="mt-4 font-semibold">Explain this entity</div>
                <!-- re-keyed on node id so the panel resets while walking neighbours -->
                @for (n of [detail.node]; track n.id) {
                    <app-assist-panel
                        intent="explain-entity"
                        [screenContext]="{ entityType: 'table', id: n.id }"
                        [userText]="'Explain ' + n.label"
                        placeholder="ask a question about this entity…">
                    </app-assist-panel>
                }
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class NodeDetailDialog {
    private api = inject(CatalogService);

    loading = false;
    detail: NodeDetail | null = null;

    readonly neighbourColumns: ColDef[] = [
        { field: 'kind', headerName: 'Kind', width: 120 },
        { field: 'label', headerName: 'Label', flex: 1 },
        { field: 'id', headerName: 'Id', flex: 1 },
    ];

    constructor() {
        const data = inject<{ id: string }>(MAT_DIALOG_DATA);
        this.load(data.id);
    }

    load(id: string): void {
        this.loading = true;
        this.detail = null;
        this.api.node(id).subscribe({
            next: (d) => { this.detail = d; this.loading = false; },
            error: () => { this.loading = false; },
        });
    }

    onNeighbourClicked(row: MetadataNode): void {
        if (row) this.load(row.id);
    }

    pretty(v: unknown): string {
        try { return JSON.stringify(v, null, 2); } catch { return String(v); }
    }
}
