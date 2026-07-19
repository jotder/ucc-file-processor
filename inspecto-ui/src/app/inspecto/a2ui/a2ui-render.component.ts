import { ChangeDetectionStrategy, Component, computed, forwardRef, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Router } from '@angular/router';
import { ColDef } from 'ag-grid-community';
import { ChartData, ChartOptions, ChartType } from 'chart.js';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { KpiComponent } from 'app/inspecto/viz/plugins/kpi.component';
import { A2uiArtifact, isRecord } from './a2ui-artifact';
import { isNavigableTarget } from './route-validation';

/** Defensive cap on `parts` recursion so a degenerate artifact can't nest unboundedly. */
const MAX_PART_DEPTH = 3;

/** A `navigate` action resolved against the route config — invalid targets render disabled. */
interface ResolvedNavigateAction {
    label: string;
    target: string;
    valid: boolean;
}

/**
 * A2UI render host (S4, spike §4.3) — the agent-emitted counterpart of `viz-render.component.ts`:
 * an `@switch` on the artifact's `kind` dispatching to the trusted design-system components.
 * The allowlist is closed (`text | kpi | chart | data-table`, mirroring the server-side one) and
 * **fail-closed**: an unknown kind — or a known kind with unusable config — degrades to the shared
 * empty-state placeholder. No agent content is ever rendered as HTML; `text` is plain text.
 *
 * `actions` render as buttons below the body — `navigate` intents only (S6 adds `invoke`), each
 * validated against the live router config; a target that doesn't resolve stays disabled. `parts`
 * render recursively, capped at {@link MAX_PART_DEPTH}.
 */
@Component({
    selector: 'inspecto-a2ui-render',
    standalone: true,
    imports: [
        MatButtonModule,
        InspectoChartComponent,
        InspectoEmptyStateComponent,
        DataTableComponent,
        KpiComponent,
        forwardRef(() => A2uiRenderComponent),
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex flex-col gap-3">
            @if (title(); as heading) {
                <div class="font-semibold">{{ heading }}</div>
            }
            @switch (kind()) {
                @case ('text') {
                    <div class="whitespace-pre-wrap">{{ text() }}</div>
                }
                @case ('kpi') {
                    <div class="h-32 max-w-64">
                        <inspecto-kpi [value]="kpiValue()" [label]="kpiLabel()" />
                    </div>
                }
                @case ('chart') {
                    @if (chartData(); as data) {
                        <inspecto-chart [type]="chartType()" [data]="data" [options]="chartOptions()" />
                    } @else {
                        <inspecto-empty-state icon="heroicons_outline:chart-bar" message="This chart has no renderable data." />
                    }
                }
                @case ('data-table') {
                    <inspecto-data-table tier="mini" [rows]="tableRows()" [columns]="tableColumns()" [autoHeight]="true" />
                }
                @default {
                    <inspecto-empty-state icon="heroicons_outline:cube-transparent" message="This component type isn't supported." />
                }
            }
            @if (nestedParts().length) {
                @for (part of nestedParts(); track $index) {
                    <inspecto-a2ui-render [artifact]="part" [depth]="depth() + 1" />
                }
            }
            @if (navigateActions().length) {
                <div class="flex flex-wrap gap-2">
                    @for (action of navigateActions(); track $index) {
                        <button mat-stroked-button [disabled]="!action.valid" (click)="navigate(action)">
                            {{ action.label }}
                        </button>
                    }
                </div>
            }
        </div>
    `,
})
export class A2uiRenderComponent {
    private router = inject(Router);

    /** The agent-emitted artifact descriptor (render-only data — see {@link A2uiArtifact}). */
    readonly artifact = input.required<A2uiArtifact>();
    /** Current nesting level — set by the recursive `parts` render, capped at {@link MAX_PART_DEPTH}. */
    readonly depth = input(0);

    /** Kind as a string (a malformed non-string kind falls to the `@default` placeholder). */
    readonly kind = computed<string>(() => {
        const k = this.artifact()?.kind;
        return typeof k === 'string' ? k : '';
    });

    readonly title = computed<string>(() => {
        const t = this.artifact()?.title;
        return typeof t === 'string' ? t : '';
    });

    /** Config as a guaranteed record — wrong-typed config degrades to `{}`, never throws. */
    private readonly config = computed<Record<string, unknown>>(() => {
        const c = this.artifact()?.config;
        return isRecord(c) ? c : {};
    });

    readonly text = computed<string>(() => {
        const t = this.config()['text'];
        return typeof t === 'string' ? t : '';
    });

    readonly kpiValue = computed<number>(() => {
        const v = Number(this.config()['value']);
        return Number.isFinite(v) ? v : 0;
    });

    readonly kpiLabel = computed<string>(() => {
        const l = this.config()['label'];
        return typeof l === 'string' && l ? l : this.title() || 'Value';
    });

    readonly chartType = computed<ChartType>(() => {
        const t = this.config()['type'];
        return (typeof t === 'string' && t ? t : 'bar') as ChartType;
    });

    /** Chart.js data from `config.data`, or null (→ placeholder) when it isn't a datasets object. */
    readonly chartData = computed<ChartData | null>(() => {
        const d = this.config()['data'];
        return isRecord(d) && Array.isArray(d['datasets']) ? (d as unknown as ChartData) : null;
    });

    readonly chartOptions = computed<ChartOptions>(() => {
        const o = this.config()['options'];
        return isRecord(o) ? (o as ChartOptions) : {};
    });

    readonly tableRows = computed<unknown[]>(() => {
        const r = this.config()['rows'];
        return Array.isArray(r) ? r.filter(isRecord) : [];
    });

    /** Columns as ag-Grid ColDefs — accepts `['a','b']` or `[{field:'a', headerName:'A'}]`;
     *  unusable entries are dropped, and no usable ones ⇒ undefined (the table derives per-key). */
    readonly tableColumns = computed<ColDef[] | undefined>(() => {
        const cols = this.config()['columns'];
        if (!Array.isArray(cols)) return undefined;
        const defs: ColDef[] = [];
        for (const col of cols) {
            if (typeof col === 'string' && col) defs.push({ field: col });
            else if (isRecord(col) && typeof col['field'] === 'string') {
                defs.push({ field: col['field'], headerName: typeof col['headerName'] === 'string' ? col['headerName'] : undefined });
            }
        }
        return defs.length ? defs : undefined;
    });

    /** Nested artifacts to render recursively — empty beyond {@link MAX_PART_DEPTH}. */
    readonly nestedParts = computed<A2uiArtifact[]>(() => {
        if (this.depth() >= MAX_PART_DEPTH) return [];
        const parts = this.artifact()?.parts;
        return Array.isArray(parts) ? parts.filter((p): p is A2uiArtifact => isRecord(p)) : [];
    });

    /** The renderable actions: `navigate` intents only, each validated against the router config.
     *  Other intents (S6 `invoke`) are hidden; an unresolvable target renders a disabled button. */
    readonly navigateActions = computed<ResolvedNavigateAction[]>(() => {
        const actions: unknown = this.artifact()?.actions;
        if (!Array.isArray(actions)) return [];
        const resolved: ResolvedNavigateAction[] = [];
        for (const a of actions as unknown[]) {
            if (!isRecord(a) || a['intent'] !== 'navigate' || typeof a['label'] !== 'string') continue;
            const target = typeof a['target'] === 'string' ? a['target'] : '';
            resolved.push({ label: a['label'], target, valid: isNavigableTarget(this.router.config, target) });
        }
        return resolved;
    });

    /** In-app router navigation only — the template disables invalid targets; this re-checks anyway. */
    navigate(action: ResolvedNavigateAction): void {
        if (action.valid) void this.router.navigateByUrl(action.target);
    }
}
