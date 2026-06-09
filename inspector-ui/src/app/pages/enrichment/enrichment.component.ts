import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { DxDataGridModule } from 'devextreme-angular/ui/data-grid';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import { DxTabsModule } from 'devextreme-angular/ui/tabs';
import { DxDateBoxModule } from 'devextreme-angular/ui/date-box';
import { DxTextBoxModule } from 'devextreme-angular/ui/text-box';
import { DxLoadIndicatorModule } from 'devextreme-angular/ui/load-indicator';
import {
  EnrichmentService, EnrichmentJobView, AuditRow, EnrichmentRunReport,
} from '../../shared/api';

type EnrTab = 'runs' | 'lineage' | 'report';

/**
 * Enrichment — Stage-2 jobs with a detail panel (runs / lineage filtered by runId / rollup report)
 * for the selected job. Generic audit rows render as dynamic-column grids; the report tab takes a
 * date range and shows percentile + throughput stats.
 */
@Component({
  standalone: true,
  imports: [
    CommonModule, DxDataGridModule, DxButtonModule, DxTabsModule,
    DxDateBoxModule, DxTextBoxModule, DxLoadIndicatorModule,
  ],
  templateUrl: './enrichment.component.html',
  styleUrls: ['./enrichment.component.scss'],
})
export class EnrichmentComponent implements OnInit {
  private api = inject(EnrichmentService);

  jobs: EnrichmentJobView[] = [];
  loading = false;
  unavailable = false;

  selected: EnrichmentJobView | null = null;

  tabs: { id: EnrTab; text: string; icon: string }[] = [
    { id: 'runs', text: 'Runs', icon: 'detailslayout' },
    { id: 'lineage', text: 'Lineage', icon: 'hierarchy' },
    { id: 'report', text: 'Report', icon: 'chart' },
  ];
  selectedIndex = 0;
  get activeTab(): EnrTab { return this.tabs[this.selectedIndex].id; }

  rows: AuditRow[] = [];
  detailLoading = false;
  lineageRunId = '';

  from: Date | null = null;
  to: Date | null = null;
  report: EnrichmentRunReport | null = null;

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading = true;
    this.unavailable = false;
    this.api.list().subscribe({
      next: (j) => { this.jobs = j; this.loading = false; },
      error: (e) => { this.loading = false; this.jobs = []; this.unavailable = e?.status === 404; },
    });
  }

  onRowClick(e: { data: EnrichmentJobView }): void {
    this.selected = e.data;
    this.selectedIndex = 0;
    this.report = null;
    this.lineageRunId = '';
    this.loadTab();
  }

  onTabChange(e: { addedItems: unknown[] }): void {
    if (e.addedItems?.length) this.loadTab();
  }

  loadTab(): void {
    if (!this.selected) return;
    const job = this.selected.name;
    if (this.activeTab === 'report') { this.loadReport(); return; }
    this.detailLoading = true;
    const call = this.activeTab === 'runs'
      ? this.api.runs(job)
      : this.api.lineage(job, this.lineageRunId.trim() || undefined);
    call.subscribe({
      next: (r) => { this.rows = r; this.detailLoading = false; },
      error: () => { this.rows = []; this.detailLoading = false; },
    });
  }

  loadReport(): void {
    if (!this.selected) return;
    this.detailLoading = true;
    const window = {
      from: this.from ? this.from.toISOString() : undefined,
      to: this.to ? this.to.toISOString() : undefined,
    };
    this.api.report(this.selected.name, window).subscribe({
      next: (r) => { this.report = r; this.detailLoading = false; },
      error: () => { this.report = null; this.detailLoading = false; },
    });
  }

  get columns(): string[] {
    return this.rows.length ? Object.keys(this.rows[0]) : [];
  }
}
