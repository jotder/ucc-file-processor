import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ColDef } from 'ag-grid-community';
import { forkJoin } from 'rxjs';
import { ComponentDef, ComponentsService, ComponentType } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { MenuBinding, PlaceableKind } from 'app/inspecto/menu';

interface PickRow {
    name: string;
    kind: PlaceableKind;
    kindLabel: string;
    componentId: string;
}

export interface MenuAttachResult {
    binding: MenuBinding;
    /** The picked artifact's display name — the default title for the new leaf. */
    title: string;
}

const KINDS: { kind: PlaceableKind; label: string }[] = [
    { kind: 'dashboard', label: 'Dashboard' },
    { kind: 'widget', label: 'Widget' },
    { kind: 'geo-map-view', label: 'Geo view' },
    { kind: 'link-analysis-view', label: 'Link view' },
];

/** Pick a library artifact (Dashboard / Widget / saved view) to place under a menu. Search + sort come
 *  from the shared data-table (standard tier); a row click selects it. */
@Component({
    selector: 'app-menu-attach-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, DataTableComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Add a report</h2>
        <mat-dialog-content>
            <p class="text-secondary mb-3 text-sm">
                Pick a dashboard, widget, or saved view to place under this menu. Search and sort to find it.
            </p>
            <inspecto-data-table
                tier="standard"
                sourceName="reports"
                exportName="reports"
                [rows]="rows()"
                [columns]="columns"
                [loading]="loading()"
                noRowsTitle="No reports found"
                noRowsHint="Create dashboards, widgets or saved views in the Studio first."
                (rowClick)="pick($event)"
            />
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="ref.close()">Cancel</button>
        </mat-dialog-actions>
    `,
})
export class MenuAttachDialog {
    readonly ref = inject<MatDialogRef<MenuAttachDialog, MenuAttachResult>>(MatDialogRef);
    private components = inject(ComponentsService);

    readonly rows = signal<PickRow[]>([]);
    readonly loading = signal(true);

    readonly columns: ColDef[] = [
        { field: 'name', headerName: 'Report', flex: 2, minWidth: 200 },
        { field: 'kindLabel', headerName: 'Type', width: 150 },
    ];

    constructor() {
        forkJoin(KINDS.map((k) => this.components.list(k.kind as ComponentType))).subscribe({
            next: (lists) => {
                const rows: PickRow[] = [];
                lists.forEach((defs: ComponentDef[], i) => {
                    const { kind, label } = KINDS[i];
                    for (const d of defs) rows.push({ name: d.name, kind, kindLabel: label, componentId: d.name });
                });
                this.rows.set(rows.sort((a, b) => a.name.localeCompare(b.name)));
                this.loading.set(false);
            },
            error: () => this.loading.set(false),
        });
    }

    pick(row: Record<string, unknown>): void {
        const r = row as unknown as PickRow;
        this.ref.close({ binding: { kind: r.kind, componentId: r.componentId }, title: r.name });
    }
}
