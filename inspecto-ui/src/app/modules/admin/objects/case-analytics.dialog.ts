import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { ChartData } from 'chart.js';
import { ObjectAnalytics, ObjectsService } from 'app/inspecto/api';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { CHART_CATEGORICAL } from 'app/inspecto/theme/chart-tokens';

interface Tile {
    label: string;
    value: string;
}

/**
 * Case analytics (C4, GLOSSARY §9) — the business-lens rollup: total / backlog / cycle-time / impact
 * stat tiles + a by-category bar, over `GET /objects/analytics?type=CASE`. Read-only; a full Studio
 * dataset binding of the same surface is the documented follow-up.
 */
@Component({
    selector: 'app-case-analytics-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, InspectoChartComponent, InspectoEmptyStateComponent, InspectoSkeletonComponent],
    template: `
        <h2 mat-dialog-title>{{ typeLabel }} analytics</h2>
        <mat-dialog-content class="pt-2" style="min-width: 34rem">
            @if (loading()) {
                <inspecto-skeleton [lines]="6" />
            } @else if (analytics(); as a) {
                <div class="grid grid-cols-2 gap-3 sm:grid-cols-3">
                    @for (t of tiles(); track t.label) {
                        <div class="bg-card rounded-2xl p-4 shadow">
                            <div class="text-secondary text-sm">{{ t.label }}</div>
                            <div class="mt-1 text-2xl font-bold">{{ t.value }}</div>
                        </div>
                    }
                </div>

                @if (categoryChart(); as chart) {
                    <div class="mt-5">
                        <div class="mb-1 text-xs font-bold uppercase tracking-wider opacity-60">By category</div>
                        <inspecto-chart type="bar" [data]="chart" [options]="chartOptions" />
                    </div>
                }

                <div class="mt-5">
                    <div class="mb-1 text-xs font-bold uppercase tracking-wider opacity-60">By status</div>
                    <dl class="grid grid-cols-[1fr_auto] gap-x-4 gap-y-0.5 text-sm">
                        @for (row of statusRows(); track row.label) {
                            <dt class="truncate">{{ row.label }}</dt>
                            <dd class="text-secondary text-right font-semibold">{{ row.count }}</dd>
                        }
                    </dl>
                </div>
            } @else {
                <inspecto-empty-state icon="heroicons_outline:chart-bar" title="No analytics"
                    message="Analytics will appear once there are cases to summarize." />
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class CaseAnalyticsDialog {
    private api = inject(ObjectsService);
    readonly data = inject<{ type: string; typeLabel: string }>(MAT_DIALOG_DATA);

    readonly typeLabel = this.data.typeLabel;
    readonly loading = signal(true);
    readonly analytics = signal<ObjectAnalytics | null>(null);

    readonly chartOptions = { plugins: { legend: { display: false } } };

    constructor() {
        this.api.analytics(this.data.type).subscribe({
            next: (a) => {
                this.analytics.set(a);
                this.loading.set(false);
            },
            error: () => this.loading.set(false),
        });
    }

    readonly tiles = computed<Tile[]>(() => {
        const a = this.analytics();
        if (!a) return [];
        return [
            { label: 'Total', value: String(a.total) },
            { label: 'Backlog (open)', value: String(a.backlog) },
            { label: 'Resolved', value: String(a.cycleTime.count) },
            { label: 'Avg cycle time', value: a.cycleTime.count ? humanizeMs(a.cycleTime.avgMs) : '—' },
            { label: 'Impact total', value: a.impact.impactAmount ? a.impact.impactAmount.toLocaleString() : '—' },
            { label: 'Records affected', value: a.impact.recordsAffected ? a.impact.recordsAffected.toLocaleString() : '—' },
        ];
    });

    readonly statusRows = computed<{ label: string; count: number }[]>(() =>
        Object.entries(this.analytics()?.byStatus ?? {}).map(([label, count]) => ({ label, count })),
    );

    readonly categoryChart = computed<ChartData | null>(() => {
        const by = this.analytics()?.byCategory ?? {};
        const labels = Object.keys(by);
        if (!labels.length) return null;
        return {
            labels,
            datasets: [{ data: labels.map((k) => by[k]), backgroundColor: labels.map((_, i) => CHART_CATEGORICAL[i % CHART_CATEGORICAL.length]) }],
        };
    });
}

/** Milliseconds → a compact "Xd Yh" / "Xh Ym" / "Xm" cycle-time label. */
function humanizeMs(ms: number): string {
    const mins = Math.round(ms / 60_000);
    if (mins < 60) return `${mins}m`;
    const hours = Math.floor(mins / 60);
    if (hours < 24) return `${hours}h ${mins % 60}m`;
    const days = Math.floor(hours / 24);
    return `${days}d ${hours % 24}h`;
}
