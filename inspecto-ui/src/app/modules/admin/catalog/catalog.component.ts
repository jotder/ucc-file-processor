import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { ColDef } from 'ag-grid-community';
import {
    CatalogService,
    GraphDirection,
    KpiCatalogEntry,
    MetadataGraph,
    MetadataNode,
    NodeKind,
} from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';
import { G6GraphData, legendFor, toG6Data } from './catalog-graph';
import { GraphViewComponent } from './graph-view.component';
import { NodeDetailDialog } from './node-detail.dialog';

type CatTab = 'tables' | 'kpis' | 'graph';

/**
 * Data catalog — the metadata graph surfaced three ways: a Tables grid (with operational
 * overlay), a KPIs grid, and a **Lineage** traversal tool (AntV G6). Any node opens a detail
 * dialog (node + 2-hop neighbours). All read-only (ASSIST_READ scope).
 */
@Component({
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTabsModule,
        DataTableComponent,
        GraphViewComponent,
    ],
    templateUrl: './catalog.component.html',
})
export class CatalogComponent implements OnInit {
    private api = inject(CatalogService);
    private dialog = inject(MatDialog);

    readonly tabs: { id: CatTab; label: string }[] = [
        { id: 'tables', label: 'Tables' },
        { id: 'kpis', label: 'KPIs' },
        { id: 'graph', label: 'Lineage' },
    ];
    tabIndex = 0;
    get activeTab(): CatTab {
        return this.tabs[this.tabIndex].id;
    }

    loading = false;
    nodes: MetadataNode[] = [];
    kpis: KpiCatalogEntry[] = [];

    // graph traversal
    graphFrom = '';
    graphDepth = 1;
    graphDirection: GraphDirection = 'both';
    graphKinds = '';
    graphEdgeKinds = '';
    graphOverlay = true;
    graph: MetadataGraph | null = null;
    readonly directions: GraphDirection[] = ['out', 'in', 'both'];

    // graph view (derived from `graph` on each successful traversal)
    g6Data: G6GraphData | null = null;
    legend: { kind: NodeKind; fill: string }[] = [];

    readonly nodeColumns: ColDef[] = [
        { field: 'kind', headerName: 'Kind', width: 130 },
        { field: 'label', headerName: 'Label', flex: 1 },
        { field: 'id', headerName: 'Id', flex: 1 },
        { field: 'overlay.freshness', headerName: 'Freshness', width: 120 },
        { field: 'overlay.rowCount', headerName: 'Rows', width: 100 },
        {
            field: 'overlay.completeness', headerName: 'Complete', width: 110,
            valueFormatter: (p) => (p.value == null ? '' : Math.round(p.value * 100) + '%'),
        },
        { field: 'overlay.lastSeen', headerName: 'Last seen', width: 180, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    readonly kpiColumns: ColDef[] = [
        { field: 'id', headerName: 'Id', width: 170 },
        { field: 'name', headerName: 'Name', flex: 1 },
        { field: 'definition', headerName: 'Definition', flex: 2, wrapText: true, autoHeight: true },
        { field: 'grain', headerName: 'Grain', width: 150 },
        { field: 'joinKeys', headerName: 'Join keys', flex: 1 },
        { field: 'inputs', headerName: 'Inputs', flex: 1 },
    ];

    ngOnInit(): void {
        this.loadTab();
    }

    loadTab(): void {
        if (this.activeTab === 'tables') {
            this.loading = true;
            this.api.tables().subscribe({
                next: (n) => { this.nodes = n; this.loading = false; },
                error: () => { this.nodes = []; this.loading = false; },
            });
        } else if (this.activeTab === 'kpis') {
            this.loading = true;
            this.api.kpis().subscribe({
                next: (k) => { this.kpis = k.kpis || []; this.loading = false; },
                error: () => { this.kpis = []; this.loading = false; },
            });
        }
    }

    runGraph(): void {
        this.loading = true;
        this.api
            .graph({
                from: this.graphFrom.trim() || undefined,
                depth: this.graphDepth,
                direction: this.graphDirection,
                kinds: this.graphKinds.trim() ? this.graphKinds.split(',').map((s) => s.trim()) : undefined,
                edgeKinds: this.graphEdgeKinds.trim() ? this.graphEdgeKinds.split(',').map((s) => s.trim()) : undefined,
                overlay: this.graphOverlay,
            })
            .subscribe({
                next: (g) => { this.setGraph(g); this.loading = false; },
                error: () => { this.setGraph(null); this.loading = false; },
            });
    }

    private setGraph(g: MetadataGraph | null): void {
        this.graph = g;
        this.g6Data = g ? toG6Data(g.nodes, g.edges) : null;
        this.legend = g ? legendFor(g.nodes) : [];
    }

    openNode(id: string): void {
        if (!id) return;
        this.dialog
            .open(NodeDetailDialog, { data: { id }, width: '760px', maxHeight: '85vh' })
            .afterClosed()
            .subscribe((nextId: string | undefined) => {
                if (nextId) this.openNode(nextId);
            });
    }

    onNodeRowClicked(row: MetadataNode): void {
        if (row) this.openNode(row.id);
    }
}
