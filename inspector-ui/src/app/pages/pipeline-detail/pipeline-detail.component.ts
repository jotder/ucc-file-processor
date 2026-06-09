import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { DxDataGridModule } from 'devextreme-angular/ui/data-grid';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import { DxTabsModule } from 'devextreme-angular/ui/tabs';
import { DxDateBoxModule } from 'devextreme-angular/ui/date-box';
import { DxTextBoxModule } from 'devextreme-angular/ui/text-box';
import { DxSelectBoxModule } from 'devextreme-angular/ui/select-box';
import { DxPopupModule } from 'devextreme-angular/ui/popup';
import { DxLoadIndicatorModule } from 'devextreme-angular/ui/load-indicator';
import notify from 'devextreme/ui/notify';
import { PipelinesService, AuditRow, BatchAuditReport, InboxStatus } from '../../shared/api';

type TabKey = 'batches' | 'files' | 'lineage' | 'quarantine' | 'commits' | 'report';
type FileFilter = 'ALL' | 'SUCCESS' | 'REJECTED' | 'ERRORED';

/**
 * Pipeline detail — tabbed view over the audit endpoints for a single pipeline:
 * batches / files / lineage (filterable by batchId) / quarantine / commits, plus a Report tab with a
 * date-range producing percentile + throughput stats.
 *
 * The Files tab adds a processing-status breakdown (processed → succeeded / rejected / errored) and a
 * filename search over the durable file audit. NB: the audit only records *processed* files — files
 * still pending in an inbox or in-flight are runtime state the backend does not expose, so they are
 * not counted here. Any row (file or batch) drills into a batch-detail drawer: the batch summary plus
 * its member files and its input→output lineage.
 */
@Component({
  standalone: true,
  imports: [
    CommonModule, DxDataGridModule, DxButtonModule, DxTabsModule, DxDateBoxModule,
    DxTextBoxModule, DxSelectBoxModule, DxPopupModule, DxLoadIndicatorModule,
  ],
  templateUrl: './pipeline-detail.component.html',
  styleUrls: ['./pipeline-detail.component.scss'],
})
export class PipelineDetailComponent implements OnInit {
  private api = inject(PipelinesService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  name = '';
  loading = false;

  tabs: { id: TabKey; text: string; icon: string }[] = [
    { id: 'batches', text: 'Batches', icon: 'detailslayout' },
    { id: 'files', text: 'Files', icon: 'file' },
    { id: 'lineage', text: 'Lineage', icon: 'hierarchy' },
    { id: 'quarantine', text: 'Quarantine', icon: 'warning' },
    { id: 'commits', text: 'Commits', icon: 'check' },
    { id: 'report', text: 'Report', icon: 'chart' },
  ];
  selectedIndex = 0;
  get activeTab(): TabKey { return this.tabs[this.selectedIndex].id; }

  rows: AuditRow[] = [];          // generic grid (batches/lineage/quarantine/commits)
  lineageBatchId = '';

  // files tab
  allFiles: AuditRow[] = [];
  inbox: InboxStatus | null = null;
  fileSearch = '';
  fileStatus: FileFilter = 'ALL';
  fileFilters: FileFilter[] = ['ALL', 'SUCCESS', 'REJECTED', 'ERRORED'];

  // report tab
  from: Date | null = null;
  to: Date | null = null;
  report: BatchAuditReport | null = null;

  // batch detail drawer
  batchDetailVisible = false;
  batchDetailLoading = false;
  batchId = '';
  batchRow: AuditRow | null = null;
  batchFiles: AuditRow[] = [];
  batchLineage: AuditRow[] = [];

  ngOnInit(): void {
    this.name = this.route.snapshot.paramMap.get('name') || '';
    this.loadTab();
  }

  onTabChange(e: { addedItems: { id: TabKey }[] }): void {
    if (e.addedItems?.length) this.loadTab();
  }

  loadTab(): void {
    const tab = this.activeTab;
    if (tab === 'report') { this.loadReport(); return; }
    if (tab === 'files') { this.loadFiles(); return; }
    this.loading = true;
    const call: Observable<AuditRow[] | string[]> =
      tab === 'batches' ? this.api.batches(this.name) :
      tab === 'lineage' ? this.api.lineage(this.name, this.lineageBatchId.trim() || undefined) :
      tab === 'quarantine' ? this.api.quarantine(this.name) :
      this.api.commits(this.name);

    call.subscribe({
      next: (data: AuditRow[] | string[]) => {
        this.rows = (data as unknown[]).map((d) =>
          typeof d === 'string' ? { commit: d } as AuditRow : d as AuditRow);
        this.loading = false;
      },
      error: () => { this.loading = false; this.rows = []; },
    });
  }

  loadFiles(): void {
    this.loading = true;
    // processed history (audit) + live inbox/processing status, together.
    forkJoin({
      files: this.api.files(this.name),
      inbox: this.api.pending(this.name),
    }).subscribe({
      next: ({ files, inbox }) => { this.allFiles = files; this.inbox = inbox; this.loading = false; },
      error: () => { this.allFiles = []; this.inbox = null; this.loading = false; },
    });
  }

  loadReport(): void {
    this.loading = true;
    const window = {
      from: this.from ? this.from.toISOString() : undefined,
      to: this.to ? this.to.toISOString() : undefined,
    };
    this.api.report(this.name, window).subscribe({
      next: (r) => { this.report = r; this.loading = false; },
      error: () => { this.loading = false; this.report = null; },
    });
  }

  // ── file-processing status ─────────────────────────────────────────────────
  private isSuccess(f: AuditRow): boolean { return (f['status'] || '').toUpperCase() === 'SUCCESS'; }
  private errorRows(f: AuditRow): number { return parseInt(f['error_rows'] || '0', 10) || 0; }

  get fileStats(): { total: number; success: number; rejected: number; errored: number; rows: number } {
    let success = 0, rejected = 0, errored = 0, rows = 0;
    for (const f of this.allFiles) {
      if (this.isSuccess(f)) success++; else rejected++;
      if (this.errorRows(f) > 0) errored++;
      rows += parseInt(f['parsed_rows'] || '0', 10) || 0;
    }
    return { total: this.allFiles.length, success, rejected, errored, rows };
  }

  get filteredFiles(): AuditRow[] {
    const q = this.fileSearch.trim().toLowerCase();
    return this.allFiles.filter((f) => {
      if (q && !(f['filename'] || '').toLowerCase().includes(q)) return false;
      switch (this.fileStatus) {
        case 'SUCCESS': return this.isSuccess(f);
        case 'REJECTED': return !this.isSuccess(f);
        case 'ERRORED': return this.errorRows(f) > 0;
        default: return true;
      }
    });
  }

  get columns(): string[] {
    return this.rows.length ? Object.keys(this.rows[0]) : [];
  }
  get fileColumns(): string[] {
    return this.allFiles.length ? Object.keys(this.allFiles[0]) : [];
  }

  // ── batch detail drawer ──────────────────────────────────────────────────────
  openBatchById(batchId?: string): void {
    const id = (batchId || '').trim();
    if (!id) { notify('No batch id on this row', 'warning', 2000); return; }
    this.batchId = id;
    this.batchRow = null;
    this.batchFiles = [];
    this.batchLineage = [];
    this.batchDetailLoading = true;
    this.batchDetailVisible = true;
    forkJoin({
      batches: this.api.batches(this.name),
      files: this.api.files(this.name),
      lineage: this.api.lineage(this.name, id),
    }).subscribe({
      next: ({ batches, files, lineage }) => {
        this.batchRow = batches.find((b) => b['batch_id'] === id) || null;
        this.batchFiles = files.filter((f) => f['batch_id'] === id);
        this.batchLineage = lineage;
        this.batchDetailLoading = false;
      },
      error: () => { this.batchDetailLoading = false; },
    });
  }

  get batchSummary(): { key: string; value: string }[] {
    return this.batchRow ? Object.entries(this.batchRow).map(([key, value]) => ({ key, value })) : [];
  }
  get batchFileColumns(): string[] {
    return this.batchFiles.length ? Object.keys(this.batchFiles[0]) : [];
  }
  get batchLineageColumns(): string[] {
    return this.batchLineage.length ? Object.keys(this.batchLineage[0]) : [];
  }

  openBatch = (e: { row: { data: AuditRow } }) => this.openBatchById(e.row.data['batch_id']);

  reprocessRow = (e: { row: { data: AuditRow } }) => {
    const id = e.row.data['batch_id'];
    if (!id) { notify('No batch id on this row', 'warning', 2000); return; }
    this.api.reprocess(this.name, id).subscribe({
      next: () => notify(`Reprocess requested for ${id}`, 'success', 2500),
    });
  };

  back(): void { this.router.navigate(['/pipelines']); }
}
