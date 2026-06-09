
import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { DxDataGridModule } from 'devextreme-angular/ui/data-grid';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import { DxToolbarModule } from 'devextreme-angular/ui/toolbar';
import { DxPopupModule } from 'devextreme-angular/ui/popup';
import { DxTextBoxModule } from 'devextreme-angular/ui/text-box';
import notify from 'devextreme/ui/notify';
import { confirm } from 'devextreme/ui/dialog';
import { PipelinesService, PipelineView, visibleInterval, DEFAULT_REFRESH_MS } from '../../shared/api';
import { AuthService } from '../../shared/services';

/**
 * Pipelines — list every configured pipeline with lifecycle actions (trigger / pause / resume /
 * reprocess) and a "Run all" toolbar. CONTROL-scoped actions are disabled when only an assist token
 * is held. Clicking a row opens the pipeline detail screen.
 */
@Component({
  standalone: true,
  imports: [DxDataGridModule, DxButtonModule, DxToolbarModule, DxPopupModule, DxTextBoxModule],
  templateUrl: './pipelines.component.html',
})
export class PipelinesComponent implements OnInit {
  private api = inject(PipelinesService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);

  pipelines: PipelineView[] = [];
  loading = false;
  autoRefresh = true;
  busy: Record<string, boolean> = {};

  reprocessVisible = false;
  reprocessTarget = '';
  reprocessBatchId = '';

  get canControl(): boolean { return this.auth.hasControl(); }

  ngOnInit(): void {
    this.load();
    visibleInterval(DEFAULT_REFRESH_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => { if (this.autoRefresh && !this.reprocessVisible) this.load(); });
  }

  load(): void {
    this.loading = true;
    this.api.list().subscribe({
      next: (p) => { this.pipelines = p; this.loading = false; },
      error: () => { this.loading = false; },
    });
  }

  async trigger(name: string): Promise<void> {
    if (!(await confirm(`Trigger pipeline <b>${name}</b> now?`, 'Trigger pipeline'))) return;
    this.busy[name] = true;
    this.api.trigger(name).subscribe({
      next: (r) => {
        notify(`${name}: ${r.total} processed, ${r.failed} failed`, r.failed ? 'warning' : 'success', 3000);
        this.busy[name] = false;
        this.load();
      },
      error: () => { this.busy[name] = false; },
    });
  }

  async runAll(): Promise<void> {
    if (!(await confirm('Trigger <b>all</b> pipelines now?', 'Run all'))) return;
    this.loading = true;
    this.api.runAll().subscribe({
      next: (res) => {
        const total = Object.values(res).reduce((s, r) => s + (r.total || 0), 0);
        const failed = Object.values(res).reduce((s, r) => s + (r.failed || 0), 0);
        notify(`Run all: ${total} processed across ${Object.keys(res).length} pipelines, ${failed} failed`,
          failed ? 'warning' : 'success', 4000);
        this.load();
      },
      error: () => { this.loading = false; },
    });
  }

  async togglePause(p: PipelineView): Promise<void> {
    const verb = p.paused ? 'Resume' : 'Pause';
    if (!(await confirm(`${verb} pipeline <b>${p.name}</b>?`, `${verb} pipeline`))) return;
    this.busy[p.name] = true;
    const call = p.paused ? this.api.resume(p.name) : this.api.pause(p.name);
    call.subscribe({
      next: (r) => {
        notify(`${p.name} ${r.paused ? 'paused' : 'resumed'}`, 'success', 2000);
        this.busy[p.name] = false;
        this.load();
      },
      error: () => { this.busy[p.name] = false; },
    });
  }

  openReprocess(name: string): void {
    this.reprocessTarget = name;
    this.reprocessBatchId = '';
    this.reprocessVisible = true;
  }

  confirmReprocess(): void {
    const id = this.reprocessBatchId.trim();
    if (!id) { notify('Enter a batch id', 'error', 2000); return; }
    this.api.reprocess(this.reprocessTarget, id).subscribe({
      next: () => {
        notify(`Reprocess requested for ${this.reprocessTarget} / ${id}`, 'success', 2500);
        this.reprocessVisible = false;
        this.load();
      },
    });
  }

  openDetail(name: string): void {
    this.router.navigate(['/pipelines', name]);
  }

  toggleAuto(): void { this.autoRefresh = !this.autoRefresh; }

  // dxDataGrid command-button handlers (receive the cell event with row.data)
  onTrigger = (e: { row: { data: PipelineView } }) => this.trigger(e.row.data.name);
  onTogglePause = (e: { row: { data: PipelineView } }) => this.togglePause(e.row.data);
  onReprocess = (e: { row: { data: PipelineView } }) => this.openReprocess(e.row.data.name);
  onOpen = (e: { row: { data: PipelineView } }) => this.openDetail(e.row.data.name);
}
