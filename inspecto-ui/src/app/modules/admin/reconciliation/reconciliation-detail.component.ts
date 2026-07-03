import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { forkJoin } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoRowAction } from 'app/inspecto/grid';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { evaluateRows } from 'app/inspecto/query';
import {
    matchedKeyCount, mergeBreaks, ReconBreak, Reconciliation, ReconciliationsService,
    resolveBreak, runReconciliation, ReconSummary, summarize,
} from 'app/inspecto/reconciliation';
import { Dataset } from '../studio/datasets/dataset-types';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { SAMPLE_SOURCES } from '../studio/datasets/dataset-sources';

/** Resolve an authored dataset to its (mock) rows — its source rows, filtered by its Query Core when virtual. */
function datasetRows(ds: Dataset | null): Record<string, unknown>[] {
    if (!ds) return [];
    const rows = SAMPLE_SOURCES[ds.sourceName] ?? [];
    if (ds.kind === 'virtual' && ds.query) return evaluateRows(ds.query, { name: ds.sourceName, rows });
    return rows;
}

/**
 * Reconciliation detail (C9) — load one reconciliation, resolve both datasets to rows, run the engine on
 * demand, and show the break report (cards) + a break drill grid. Running merges the fresh breaks with the
 * previous run so re-matched keys auto-close and manual resolutions carry forward; the result is persisted.
 */
@Component({
    selector: 'app-reconciliation-detail',
    standalone: true,
    imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatTooltipModule, DataTableComponent, InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './reconciliation-detail.component.html',
})
export class ReconciliationDetailComponent implements OnInit {
    private api = inject(ReconciliationsService);
    private datasetsApi = inject(DatasetsService);
    private route = inject(ActivatedRoute);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    readonly recon = signal<Reconciliation | null>(null);
    readonly loading = signal(false);
    readonly running = signal(false);
    private leftRows: Record<string, unknown>[] = [];
    private rightRows: Record<string, unknown>[] = [];

    /** Report cards — null until the reconciliation has been run at least once. */
    readonly summary = signal<ReconSummary | null>(null);

    /** Only the actionable (non-auto-closed) breaks show in the drill grid. */
    readonly drillBreaks = computed(() => (this.recon()?.breaks ?? []).filter((b) => b.status !== 'auto_closed'));

    readonly columns: ColDef<ReconBreak>[] = [
        { field: 'key', headerName: 'Key', width: 120 },
        {
            field: 'type', headerName: 'Break', width: 150,
            cellRenderer: (p: ICellRendererParams<ReconBreak>) => statusBadgeHtml(breakLabel(p.value as string)),
        },
        { field: 'column', headerName: 'Column', width: 130, valueFormatter: (p) => p.value ?? '—' },
        { field: 'leftValue', headerName: 'Left', flex: 1, valueFormatter: (p) => fmtVal(p.value) },
        { field: 'rightValue', headerName: 'Right', flex: 1, valueFormatter: (p) => fmtVal(p.value) },
        { field: 'diff', headerName: 'Diff', width: 110, valueFormatter: (p) => (p.value == null ? '—' : String(p.value)) },
        {
            field: 'status', headerName: 'Status', width: 120,
            cellRenderer: (p: ICellRendererParams<ReconBreak>) => statusBadgeHtml(p.value as string),
        },
    ];

    readonly rowActions: InspectoRowAction<ReconBreak>[] = [
        {
            icon: (b) => (b.status === 'resolved' ? 'heroicons_outline:arrow-uturn-left' : 'heroicons_outline:check'),
            hint: (b) => (b.status === 'resolved' ? 'Re-open' : 'Resolve'),
            onClick: (b) => this.toggleResolve(b),
        },
    ];

    ngOnInit(): void {
        const id = this.route.snapshot.paramMap.get('id') ?? '';
        this.loading.set(true);
        this.api.get(id).subscribe({
            next: (r) => {
                this.recon.set(r);
                this.loadRows(r);
                if (r.breaks.length) this.recomputeSummary();
            },
            error: (e) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, `Could not load reconciliation "${id}"`));
            },
        });
    }

    private loadRows(r: Reconciliation): void {
        forkJoin({ left: this.datasetsApi.get(r.leftDataset), right: this.datasetsApi.get(r.rightDataset) }).subscribe({
            next: ({ left, right }) => {
                this.leftRows = datasetRows(left);
                this.rightRows = datasetRows(right);
                this.loading.set(false);
            },
            error: () => {
                this.leftRows = [];
                this.rightRows = [];
                this.loading.set(false);
            },
        });
    }

    run(): void {
        const r = this.recon();
        if (!r) return;
        this.running.set(true);
        const fresh = runReconciliation(r, this.leftRows, this.rightRows);
        const merged = mergeBreaks(r.breaks, fresh);
        const updated: Reconciliation = { ...r, breaks: merged, lastRunAt: new Date().toISOString() };
        this.api.save(updated).subscribe({
            next: () => {
                this.recon.set(updated);
                this.recomputeSummary();
                this.running.set(false);
                this.toastr.success(`Reconciliation run — ${this.summary()?.open ?? 0} open break(s)`);
            },
            error: (e) => {
                this.running.set(false);
                this.toastr.error(apiErrorMessage(e, 'Reconciliation run failed'));
            },
        });
    }

    private recomputeSummary(): void {
        const r = this.recon();
        if (!r) return;
        this.summary.set(summarize(r.breaks, this.leftRows.length, this.rightRows.length, matchedKeyCount(r.keyColumns, this.leftRows, this.rightRows)));
    }

    async toggleResolve(b: ReconBreak): Promise<void> {
        const r = this.recon();
        if (!r) return;
        const resolving = b.status !== 'resolved';
        if (resolving && !(await this.confirm.confirm(`Mark this ${breakLabel(b.type)} for key "${b.key}" resolved?`, 'Resolve break'))) return;
        const updated: Reconciliation = { ...r, breaks: resolveBreak(r.breaks, b, resolving) };
        this.api.save(updated).subscribe({
            next: () => {
                this.recon.set(updated);
                this.recomputeSummary();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not update the break')),
        });
    }
}

function breakLabel(type: string): string {
    return type === 'missing_left' ? 'missing left' : type === 'missing_right' ? 'missing right' : type === 'value_break' ? 'value break' : type;
}
function fmtVal(v: unknown): string {
    return v == null ? '—' : String(v);
}
