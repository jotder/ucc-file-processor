import { CommonModule } from '@angular/common';
import { Component, Input, OnInit, inject } from '@angular/core';
import { DxTextAreaModule } from 'devextreme-angular/ui/text-area';
import { DxButtonModule } from 'devextreme-angular/ui/button';
import { DxCheckBoxModule } from 'devextreme-angular/ui/check-box';
import { DxDataGridModule } from 'devextreme-angular/ui/data-grid';
import { DxLoadIndicatorModule } from 'devextreme-angular/ui/load-indicator';
import notify from 'devextreme/ui/notify';
import { AssistService, AssistResult, AssistIntent } from '../../api';

/** SQL-producing intents that can return a sampleRows preview. */
const SQL_INTENTS = ['kpi-to-sql', 'report-sql'];

/**
 * Reusable assist panel — a self-contained "ask the agent" widget for any of the 7 intents. It
 * renders the input (free text + optional sample-rows toggle for SQL intents), POSTs to
 * /assist/{intent}, and renders the AssistResult with intent-aware sections: SQL + sample grid,
 * draft .toon (copyable), nextRuns, human-readable summary, narrative prose, findings, plus a raw
 * data fallback. Degrades gracefully on 503 (agent absent) / 404 (unknown intent). All draft-only.
 */
@Component({
  selector: 'app-assist-panel',
  standalone: true,
  imports: [
    CommonModule, DxTextAreaModule, DxButtonModule, DxCheckBoxModule,
    DxDataGridModule, DxLoadIndicatorModule,
  ],
  templateUrl: './assist-panel.component.html',
  styleUrls: ['./assist-panel.component.scss'],
})
export class AssistPanelComponent implements OnInit {
  private api = inject(AssistService);

  @Input() intent!: AssistIntent | string;
  @Input() placeholder = 'Describe what you need…';
  @Input() userText = '';
  @Input() screenContext: Record<string, unknown> = {};
  @Input() basePartialInput: Record<string, unknown> = {};
  /** run once on init with no further input (contextual embeds, e.g. explain-entity). */
  @Input() autoRun = false;
  /** hide the free-text input (pure contextual embed). */
  @Input() hideInput = false;

  loading = false;
  result: AssistResult | null = null;

  get isSql(): boolean { return SQL_INTENTS.includes(this.intent); }
  wantSample = false;

  ngOnInit(): void {
    if (this.autoRun) this.run();
  }

  run(): void {
    const text = this.userText.trim();
    if (!this.autoRun && !this.hideInput && !text) {
      notify('Enter a request', 'warning', 2000);
      return;
    }
    const partialInput: Record<string, unknown> = { ...this.basePartialInput };
    if (this.isSql) {
      if (text && partialInput['kpiDescription'] === undefined) partialInput['kpiDescription'] = text;
      if (this.wantSample) partialInput['sampleRows'] = true;
    }
    this.loading = true;
    this.result = null;
    this.api.run(this.intent, {
      screenContext: this.screenContext,
      partialInput,
      userText: text || undefined,
    }).subscribe({
      next: (r) => { this.result = r; this.loading = false; },
      error: (e) => {
        this.loading = false;
        notify(
          e?.status === 503 ? 'Assist agent is not available (file-processor-agent absent).'
          : e?.status === 404 ? `Unknown assist intent "${this.intent}".`
          : 'Assist request failed.',
          'error', 3500);
      },
    });
  }

  // ── data accessors (data is a loose Record) ─────────────────────────────────
  private d<T = unknown>(key: string): T | undefined {
    return this.result?.data?.[key] as T | undefined;
  }
  get sql(): string | undefined { return this.d<string>('sql'); }
  get draftToon(): string | undefined { return this.d<string>('draftToon'); }
  get humanReadable(): string | undefined { return this.d<string>('humanReadable'); }
  get narrative(): string | undefined { return this.d<string>('narrative'); }
  get nextRuns(): string[] { return (this.d<string[]>('nextRuns')) || []; }
  get sampleRows(): Record<string, unknown>[] { return (this.d<Record<string, unknown>[]>('sampleRows')) || []; }
  get findings(): { severity?: string; fieldPath?: string; message?: string }[] {
    return (this.d<{ severity?: string; fieldPath?: string; message?: string }[]>('findings')) || [];
  }
  get sampleColumns(): string[] {
    return this.sampleRows.length ? Object.keys(this.sampleRows[0]) : [];
  }

  copy(text?: string): void {
    if (!text) return;
    navigator.clipboard?.writeText(text).then(
      () => notify('Copied to clipboard', 'success', 1500),
      () => notify('Clipboard unavailable', 'warning', 1500),
    );
  }

  pretty(v: unknown): string {
    try { return JSON.stringify(v, null, 2); } catch { return String(v); }
  }
}
