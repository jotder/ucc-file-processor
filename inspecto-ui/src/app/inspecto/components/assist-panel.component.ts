import { Component, inject, Input, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';
import { AssistIntent, AssistResult, AssistService } from 'app/inspecto/api';
import { A2uiArtifact, isRecord } from 'app/inspecto/a2ui/a2ui-artifact';
import { A2uiRenderComponent } from 'app/inspecto/a2ui/a2ui-render.component';

/** SQL-producing intents that can return a sampleRows preview. */
const SQL_INTENTS = ['kpi-to-sql', 'report-sql'];

/**
 * Reusable assist panel — a self-contained "ask the agent" widget for any of the 7 intents
 * (ported from inspector-ui onto Material + Tailwind). It renders the input (free text + optional
 * sample-rows toggle for SQL intents), POSTs to /assist/{intent}, and renders the AssistResult.
 * The artifact-shaped sections (humanReadable / narrative / sampleRows / findings — or a
 * server-shaped `result.artifact`) render through the generic `<inspecto-a2ui-render>` host (S7);
 * the result-level chrome (status header, message/rationale, SQL + draft .toon copy blocks,
 * nextRuns, citations/links, raw fallback) stays panel-owned. Degrades gracefully on 503 (agent
 * absent) / 404 (unknown intent). All draft-only.
 */
@Component({
    selector: 'app-assist-panel',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        A2uiRenderComponent,
    ],
    templateUrl: './assist-panel.component.html',
})
export class AssistPanelComponent implements OnInit {
    private api = inject(AssistService);
    private toastr = inject(ToastrService);

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

    get isSql(): boolean {
        return SQL_INTENTS.includes(this.intent);
    }
    wantSample = false;

    ngOnInit(): void {
        if (this.autoRun) this.run();
    }

    run(): void {
        const text = this.userText.trim();
        if (!this.autoRun && !this.hideInput && !text) {
            this.toastr.warning('Enter a request');
            return;
        }
        const partialInput: Record<string, unknown> = { ...this.basePartialInput };
        if (this.isSql) {
            if (text && partialInput['kpiDescription'] === undefined) partialInput['kpiDescription'] = text;
            if (this.wantSample) partialInput['sampleRows'] = true;
        }
        this.loading = true;
        this.result = null;
        this.api
            .run(this.intent, {
                screenContext: this.screenContext,
                partialInput,
                userText: text || undefined,
            })
            .subscribe({
                next: (r) => {
                    this.result = r;
                    this.loading = false;
                },
                error: (e) => {
                    this.loading = false;
                    this.toastr.error(
                        e?.status === 503 ? 'Assist agent is not available (file-processor-agent absent).'
                        : e?.status === 404 ? `Unknown assist intent "${this.intent}".`
                        : 'Assist request failed.',
                    );
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
    get nextRuns(): string[] { return this.d<string[]>('nextRuns') || []; }
    get sampleRows(): Record<string, unknown>[] { return this.d<Record<string, unknown>[]>('sampleRows') || []; }
    get findings(): { severity?: string; fieldPath?: string; message?: string }[] {
        return this.d<{ severity?: string; fieldPath?: string; message?: string }[]>('findings') || [];
    }
    get sampleColumns(): string[] {
        return this.sampleRows.length ? Object.keys(this.sampleRows[0]) : [];
    }

    /**
     * The result's renderable A2UI artifact (S7): a server-shaped `result.artifact` (or legacy
     * `data.artifact`) is used as-is; otherwise the migrated data-bag sections (humanReadable /
     * narrative / sampleRows / findings) compose into one artifact rendered through the generic
     * `<inspecto-a2ui-render>` host. Null when there is nothing artifact-shaped to render.
     */
    get artifact(): A2uiArtifact | null {
        if (!this.result) return null;
        const served = this.result.artifact ?? this.d('artifact');
        if (isRecord(served) && typeof served['kind'] === 'string') return served as unknown as A2uiArtifact;
        const parts: A2uiArtifact[] = [];
        if (this.humanReadable) parts.push({ kind: 'text', config: { text: this.humanReadable } });
        if (this.narrative) parts.push({ kind: 'text', config: { text: this.narrative } });
        if (this.sampleRows.length) {
            parts.push({
                kind: 'data-table',
                title: `Sample rows (${this.sampleRows.length})`,
                config: { rows: this.sampleRows, columns: this.sampleColumns },
            });
        }
        if (this.findings.length) {
            parts.push({
                kind: 'data-table',
                title: 'Findings',
                config: { rows: this.findings, columns: ['severity', 'fieldPath', 'message'] },
            });
        }
        if (!parts.length) return null;
        return parts.length === 1 ? parts[0] : { kind: 'text', config: {}, parts };
    }

    copy(text?: string): void {
        if (!text) return;
        navigator.clipboard?.writeText(text).then(
            () => this.toastr.success('Copied to clipboard'),
            () => this.toastr.warning('Clipboard unavailable'),
        );
    }

    pretty(v: unknown): string {
        try {
            return JSON.stringify(v, null, 2);
        } catch {
            return String(v);
        }
    }
}
