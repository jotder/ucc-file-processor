
import { Component, OnInit, inject } from '@angular/core';
import { DxDataGridModule } from 'devextreme-angular/ui/data-grid';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import { DxTabsModule } from 'devextreme-angular/ui/tabs';
import { DxPopupModule } from 'devextreme-angular/ui/popup';
import { DxTextBoxModule } from 'devextreme-angular/ui/text-box';
import { DxNumberBoxModule } from 'devextreme-angular/ui/number-box';
import { DxSelectBoxModule } from 'devextreme-angular/ui/select-box';
import { DxCheckBoxModule } from 'devextreme-angular/ui/check-box';
import { DxLoadIndicatorModule } from 'devextreme-angular/ui/load-indicator';
import { DxDiagramModule } from 'devextreme-angular/ui/diagram';
import {
  CatalogService, MetadataNode, NodeKind, KpiCatalogEntry, NodeDetail, MetadataGraph, GraphDirection,
} from '../../shared/api';
import { AssistPanelComponent } from '../../shared/components';
import {
  DiagramEdge, DiagramNode, legendFor, toDiagramEdges, toDiagramNodes,
} from './catalog-graph';

type CatTab = 'tables' | 'kpis' | 'graph';

/**
 * Data catalog — the metadata graph surfaced three ways: a Tables grid (with operational overlay),
 * a KPIs grid, and a Graph traversal tool. Any node opens a detail drawer (node + 2-hop neighbours).
 * All read-only (ASSIST_READ scope).
 */
@Component({
  standalone: true,
  imports: [
    DxDataGridModule,
    DxButtonModule,
    DxTabsModule,
    DxPopupModule,
    DxTextBoxModule,
    DxNumberBoxModule,
    DxSelectBoxModule,
    DxCheckBoxModule,
    DxLoadIndicatorModule,
    DxDiagramModule,
    AssistPanelComponent
],
  templateUrl: './catalog.component.html',
  styleUrls: ['./catalog.component.scss'],
})
export class CatalogComponent implements OnInit {
  private api = inject(CatalogService);

  tabs: { id: CatTab; text: string; icon: string }[] = [
    { id: 'tables', text: 'Tables', icon: 'detailslayout' },
    { id: 'kpis', text: 'KPIs', icon: 'variable' },
    { id: 'graph', text: 'Graph', icon: 'hierarchy' },
  ];
  selectedIndex = 0;
  get activeTab(): CatTab { return this.tabs[this.selectedIndex].id; }

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
  directions: GraphDirection[] = ['out', 'in', 'both'];

  // graph diagram (derived from `graph` on each successful traversal)
  diagramNodes: DiagramNode[] = [];
  diagramEdges: DiagramEdge[] = [];
  legend: { kind: NodeKind; fill: string }[] = [];

  // node detail
  detailVisible = false;
  detail: NodeDetail | null = null;
  detailLoading = false;

  ngOnInit(): void { this.loadTab(); }

  onTabChange(e: { addedItems: unknown[] }): void {
    if (e.addedItems?.length) this.loadTab();
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
    this.api.graph({
      from: this.graphFrom.trim() || undefined,
      depth: this.graphDepth,
      direction: this.graphDirection,
      kinds: this.graphKinds.trim() ? this.graphKinds.split(',').map((s) => s.trim()) : undefined,
      edgeKinds: this.graphEdgeKinds.trim() ? this.graphEdgeKinds.split(',').map((s) => s.trim()) : undefined,
      overlay: this.graphOverlay,
    }).subscribe({
      next: (g) => { this.setGraph(g); this.loading = false; },
      error: () => { this.setGraph(null); this.loading = false; },
    });
  }

  private setGraph(g: MetadataGraph | null): void {
    this.graph = g;
    this.diagramNodes = g ? toDiagramNodes(g.nodes) : [];
    this.diagramEdges = g ? toDiagramEdges(g.edges) : [];
    this.legend = g ? legendFor(g.nodes) : [];
  }

  /** Open the detail drawer when a node shape is clicked; ignore connector clicks. */
  onDiagramItemClick = (e: { item?: { itemType?: string; dataItem?: { id?: string } } }) => {
    if (e.item?.itemType === 'shape' && e.item.dataItem?.id) this.openNode(e.item.dataItem.id);
  };

  openNode(id: string): void {
    if (!id) return;
    this.detail = null;
    this.detailLoading = true;
    this.detailVisible = true;
    this.api.node(id).subscribe({
      next: (d) => { this.detail = d; this.detailLoading = false; },
      error: () => { this.detailLoading = false; },
    });
  }

  onNodeRowClick = (e: { data: MetadataNode }) => this.openNode(e.data.id);

  trackNode = (_: number, n: MetadataNode) => n.id;

  pretty(v: unknown): string {
    try { return JSON.stringify(v, null, 2); } catch { return String(v); }
  }
}
