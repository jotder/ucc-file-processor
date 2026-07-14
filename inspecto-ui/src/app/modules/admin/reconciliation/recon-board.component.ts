import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoRowAction } from 'app/inspecto/grid';
import { FlatTreeRow, TreeNode, TreeTableComponent } from 'app/inspecto/tree-table';
import {
    bandCell, bandFor, breaksFromSets, buildBoardTree, DEFAULT_BANDS, deltaPct, fmtMeasure,
    markBreachesExpanded, mergeBreaks, ReconBand, Reconciliation, ReconciliationsService,
    ReconRunResult, RECON_RECORDS,
} from 'app/inspecto/reconciliation';
import { ReconExecService } from './recon-exec.service';
import { ReconciliationFormDialog, ReconciliationFormResult } from './reconciliation-form.dialog';

/** Text-tone classes per band (glyph + text carry the meaning; tones are lint-sanctioned `text-*`). */
const BAND_TONES: Record<ReconBand, string> = {
    ok: 'text-green-600 dark:text-green-400',
    warn: 'text-amber-600 dark:text-amber-400',
    breach: 'text-red-600 dark:text-red-400 font-semibold',
    structural: 'text-red-600 dark:text-red-400',
};
const BAND_GLYPHS: Record<ReconBand, string> = { ok: '✓', warn: '!', breach: '✕', structural: '⊘' };

/** One measure line of the pinned TOTAL strip. */
interface TotalLine {
    label: string;
    a: string;
    b: string;
    pct: string;
    tone: string;
    glyph: string;
}

/**
 * Reconciliation Board (`/reconciliation/:id`) — the aggregate comparison tree: key columns in selection
 * order form the hierarchy; each compare column shows both sides + a banded Δ% vs the anchor (A).
 * Runs on open via {@link ReconExecService} (server DuckDB, or the offline mirror under mock Studio);
 * the details action drills to the Breaks page carrying the encoded dimension path.
 * Design: `docs/superpower/reconciliation-board-design.md` §4.
 */
@Component({
    selector: 'app-recon-board',
    standalone: true,
    imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatSlideToggleModule,
        MatTooltipModule, TreeTableComponent, InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './recon-board.component.html',
})
export class ReconBoardComponent implements OnInit {
    private reconApi = inject(ReconciliationsService);
    private exec = inject(ReconExecService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);

    private tree = viewChild(TreeTableComponent);

    readonly recon = signal<Reconciliation | null>(null);
    readonly result = signal<ReconRunResult | null>(null);
    readonly loading = signal(true);
    readonly running = signal(false);
    readonly breachesOnly = signal(false);

    readonly bands = computed(() => this.recon()?.bands ?? DEFAULT_BANDS);

    readonly treeNodes = computed<TreeNode[]>(() => {
        const r = this.result();
        if (!r) return [];
        const nodes = buildBoardTree(r, this.bands());
        return this.breachesOnly() ? markBreachesExpanded(nodes, this.bands()) : nodes;
    });

    readonly treeColumns = computed<ColDef[]>(() => {
        const r = this.result();
        if (!r) return [];
        const cols: ColDef[] = [];
        for (const m of r.measures) {
            const label = m === RECON_RECORDS ? 'records' : m;
            cols.push(
                { field: `a_${m}`, headerName: `A ${label}`, width: 130, valueFormatter: (p) => fmtMeasure(p.value) },
                { field: `b_${m}`, headerName: `B ${label}`, width: 130, valueFormatter: (p) => fmtMeasure(p.value) },
                { field: `pct_${m}`, headerName: `Δ% ${label}`, width: 130, cellRenderer: bandCell(this.bands()) },
            );
        }
        return cols;
    });

    readonly totalLines = computed<TotalLine[]>(() => {
        const r = this.result();
        if (!r) return [];
        return r.measures.map((m) => {
            const a = r.totals.a[m];
            const b = r.totals.b[m];
            const pct = deltaPct(a, b);
            const band = pct === null ? 'structural' : bandFor(pct, this.bands());
            return {
                label: m === RECON_RECORDS ? 'records' : m,
                a: fmtMeasure(a),
                b: fmtMeasure(b),
                pct: pct === null ? 'n/a' : `${pct > 0 ? '+' : ''}${pct.toFixed(1)}%`,
                tone: BAND_TONES[band],
                glyph: BAND_GLYPHS[band],
            };
        });
    });

    readonly rowActions: InspectoRowAction<FlatTreeRow>[] = [
        {
            icon: 'heroicons_outline:magnifying-glass',
            hint: 'View breaks under this path',
            onClick: (row) => this.viewBreaks(String(row['__path'] ?? '')),
        },
    ];

    ngOnInit(): void {
        const id = this.route.snapshot.paramMap.get('id') ?? '';
        this.reconApi.get(id).subscribe({
            next: (r) => {
                this.recon.set(r);
                this.loading.set(false);
                void this.run();
            },
            error: (e) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, `Could not load reconciliation "${id}"`));
            },
        });
    }

    /**
     * Run the aggregate comparison AND refresh the persisted Break lifecycle (the locked C9 semantics:
     * fresh breaks merge with the previous run — re-matched keys auto-close, manual resolutions carry
     * forward). The Board run is the one full-scope run, so the merge happens here; the Breaks page is
     * a live viewer. A failed lifecycle save degrades gracefully — the Board still renders.
     */
    async run(): Promise<void> {
        const r = this.recon();
        if (!r || this.running()) return;
        this.running.set(true);
        try {
            const [result, sets] = await Promise.all([this.exec.run(r), this.exec.breaks(r)]);
            this.result.set(result);
            const updated: Reconciliation = {
                ...r,
                breaks: mergeBreaks(r.breaks, breaksFromSets(r, sets)),
                lastRunAt: new Date().toISOString(),
            };
            this.reconApi.save(updated).subscribe({
                next: () => this.recon.set(updated),
                error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not persist the break lifecycle')),
            });
        } catch (e) {
            this.result.set(null);
            this.toastr.error(apiErrorMessage(e, 'Reconciliation run failed'));
        } finally {
            this.running.set(false);
        }
    }

    edit(): void {
        const r = this.recon();
        if (!r) return;
        this.dialog
            .open(ReconciliationFormDialog, { width: '640px', maxHeight: '85vh', data: { recon: r } })
            .afterClosed()
            .subscribe((result?: ReconciliationFormResult) => {
                if (!result) return;
                const updated: Reconciliation = {
                    ...r,
                    name: result.name,
                    leftDataset: result.leftDataset,
                    rightDataset: result.rightDataset,
                    keyColumns: result.keyColumns,
                    compareColumns: result.compareColumns,
                    bands: result.bands,
                };
                this.reconApi.save(updated).subscribe({
                    next: () => {
                        this.recon.set(updated);
                        void this.run();
                    },
                    error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not save the reconciliation')),
                });
            });
    }

    viewBreaks(path?: string): void {
        const r = this.recon();
        if (!r) return;
        void this.router.navigate(['/reconciliation', r.id, 'breaks'], path ? { queryParams: { path } } : {});
    }

    expandAll(): void {
        this.tree()?.expandAll();
    }

    collapseAll(): void {
        this.tree()?.collapseAll();
    }

    exportCsv(): void {
        this.tree()?.exportCsv();
    }
}
