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
import { Router } from '@angular/router';
import { ColDef } from 'ag-grid-community';
import {
    CatalogService,
    GraphDirection,
    KpiCatalogEntry,
    LensService,
    MetadataGraph,
    MetadataNode,
    NodeKind,
    SessionService,
} from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoRowAction, fmtDateTime } from 'app/inspecto/grid';
import { OnboardingCreateDialog, OnboardingCreateResult } from './onboarding/onboarding-create.dialog';
import { RegistryComponent } from './registry.component';
import { G6GraphData, legendFor, toG6Data } from './catalog-graph';
import { GraphViewComponent } from './graph-view.component';
import { NodeDetailDialog } from './node-detail.dialog';
import { SharingComponent } from './sharing.component';

type CatTab = 'tables' | 'streams' | 'references' | 'kpis' | 'graph' | 'usage' | 'shared-with-me' | 'shared-by-me';

/**
 * Data catalog — the metadata graph surfaced several ways: a Tables grid (with operational
 * overlay), a **Streams** grid (event/fact data origins — Collector + Connection, browsed by name), a
 * **References** grid (dimension data origins — REFERENCE_DATASET, browsed by name), a KPIs grid, a
 * **Lineage** traversal tool (AntV G6), and a **Usage/Reuse** lens (the former standalone Registry
 * page, embedded — Catalog's definition includes "usage"). Any node opens a detail dialog (node +
 * 2-hop neighbours). All read-only (ASSIST_READ scope).
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
        InspectoEmptyStateComponent,
        GraphViewComponent,
        RegistryComponent,
        SharingComponent,
    ],
    templateUrl: './catalog.component.html',
})
export class CatalogComponent implements OnInit {
    private api = inject(CatalogService);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    protected lens = inject(LensService);

    // The two Exchange tabs appear only on a multi-space runtime (bootstrap.features.exchange —
    // resolved before the app initializes, so the list is stable for the component's lifetime).
    // Streams leads and is the default tab (stream-onboarding D2): data origins are the Catalog's
    // front door; Tables and the rest follow.
    readonly tabs: { id: CatTab; label: string }[] = [
        { id: 'streams', label: 'Streams' },
        { id: 'references', label: 'References' },
        { id: 'tables', label: 'Tables' },
        { id: 'kpis', label: 'KPIs' },
        { id: 'graph', label: 'Lineage' },
        { id: 'usage', label: 'Usage' },
        ...(inject(SessionService).exchangeEnabled()
            ? [
                  { id: 'shared-with-me' as CatTab, label: 'Shared with me' },
                  { id: 'shared-by-me' as CatTab, label: 'Shared by me' },
              ]
            : []),
    ];
    tabIndex = 0;
    get activeTab(): CatTab {
        return this.tabs[this.tabIndex].id;
    }

    loading = false;
    nodes: MetadataNode[] = [];
    streams: MetadataNode[] = [];
    references: MetadataNode[] = [];
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

    readonly streamColumns: ColDef[] = [
        { field: 'label', headerName: 'Stream', flex: 1 },
        {
            headerName: 'Lifecycle',
            width: 110,
            valueGetter: (p) => lifecycleOf((p.data as MetadataNode)?.attrs),
            cellRenderer: (p: { value: string }) => (p.value ? statusBadgeHtml(p.value) : '—'),
        },
        { field: 'attrs.connector', headerName: 'Connector', width: 130 },
        { field: 'attrs.connection', headerName: 'Connection', flex: 1, valueFormatter: (p) => p.value ?? '—' },
        { field: 'attrs.pipeline', headerName: 'Pipeline', flex: 1 },
        { field: 'description.text', headerName: 'Description', flex: 2 },
    ];

    readonly referenceColumns: ColDef[] = [
        { field: 'label', headerName: 'Reference', flex: 1 },
        {
            // Pipeline-produced references carry the producer's active flag (P3); path/dangling
            // enrichment-scoped rows have no lifecycle and render '—'.
            headerName: 'Lifecycle',
            width: 110,
            valueGetter: (p) => lifecycleOf((p.data as MetadataNode)?.attrs),
            cellRenderer: (p: { value: string }) => (p.value ? statusBadgeHtml(p.value) : '—'),
        },
        { field: 'attrs.connector', headerName: 'Connector', width: 130 },
        { field: 'attrs.connection', headerName: 'Connection', flex: 1, valueFormatter: (p) => p.value ?? '—' },
        { field: 'attrs.pipeline', headerName: 'Pipeline', flex: 1 },
        { field: 'description.text', headerName: 'Description', flex: 2 },
    ];

    /** Resume/open the guided onboarding for a data-origin row (its backing pipeline). */
    readonly originRowActions: InspectoRowAction<MetadataNode>[] = [
        {
            icon: 'heroicons_outline:pencil-square',
            hint: (row) => (row.attrs?.['active'] === false ? 'Resume onboarding' : 'Open onboarding'),
            visible: (row) => this.lens.canAuthorWorkbench() && !!row.attrs?.['pipeline'],
            onClick: (row) => this.router.navigate(['/catalog', 'onboard', String(row.attrs?.['pipeline'])]),
        },
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
        } else if (this.activeTab === 'streams') {
            this.loading = true;
            this.api.streams().subscribe({
                next: (s) => { this.streams = s; this.loading = false; },
                error: () => { this.streams = []; this.loading = false; },
            });
        } else if (this.activeTab === 'references') {
            this.loading = true;
            this.api.references().subscribe({
                next: (r) => { this.references = r; this.loading = false; },
                error: () => { this.references = []; this.loading = false; },
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

    /** Header CTA (Streams/References tabs, authoring lenses): open the guided onboarding. */
    onboard(kind: 'stream' | 'reference'): void {
        if (!this.lens.canAuthorWorkbench()) return;
        const existingNames = [...this.streams, ...this.references]
            .map((n) => String(n.attrs?.['pipeline'] ?? ''))
            .filter(Boolean);
        this.dialog
            .open<OnboardingCreateDialog, unknown, OnboardingCreateResult>(OnboardingCreateDialog, {
                data: { kind, existingNames },
                width: '560px',
                maxWidth: '95vw',
            })
            .afterClosed()
            .subscribe((res) => {
                if (res?.name) this.router.navigate(['/catalog', 'onboard', res.name]);
            });
    }
}

/** Draft (`active:false`) / Live (`active:true`); blank when the row carries no active flag. */
function lifecycleOf(attrs: Record<string, unknown> | undefined): string {
    if (!attrs || attrs['active'] === undefined) return '';
    return attrs['active'] === false ? 'Draft' : 'Live';
}
