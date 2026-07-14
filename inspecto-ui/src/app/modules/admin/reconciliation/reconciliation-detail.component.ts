import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { FlatTreeRow, TreeNode, TreeTableComponent, varianceCell } from 'app/inspecto/tree-table';
import { InspectoRowAction } from 'app/inspecto/grid';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import {
    breakId, breaksFromSets, decodePath, Reconciliation, ReconciliationsService, ReconBreak,
    resolveBreak,
} from 'app/inspecto/reconciliation';
import { ReconExecService } from './recon-exec.service';

/**
 * Breaks page (`/reconciliation/:id/breaks?path=…`) — the record sets behind one Board cell: three
 * tables (only in A / only in B / matched-but-different), computed live at the recon grain by
 * {@link ReconExecService} (server DuckDB, or the offline mirror under mock Studio) and optionally
 * scoped to a Board dimension path. The persisted Break lifecycle overlays by identity — resolve /
 * re-open persists here; auto-close happens on the Board's full-scope run (C9 semantics unchanged).
 * The grouped tree stays as a display toggle. Design §5.
 */
@Component({
    selector: 'app-reconciliation-detail',
    standalone: true,
    imports: [RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatProgressSpinnerModule,
        MatTooltipModule, DataTableComponent, TreeTableComponent, InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './reconciliation-detail.component.html',
})
export class ReconciliationDetailComponent implements OnInit {
    private api = inject(ReconciliationsService);
    private exec = inject(ReconExecService);
    private route = inject(ActivatedRoute);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    readonly recon = signal<Reconciliation | null>(null);
    readonly loading = signal(true);
    readonly computing = signal(false);
    /** Live breaks (all `open` from the engine) — persisted statuses overlay by identity below. */
    private readonly liveBreaks = signal<ReconBreak[] | null>(null);

    /** The Board dimension path this page is scoped to (from `?path=`), or null for the whole recon. */
    readonly path = signal<Record<string, string> | null>(null);
    readonly pathEntries = computed(() => Object.entries(this.path() ?? {}));

    readonly viewMode = signal<'tables' | 'grouped'>('tables');

    /** Persisted break status/note by identity. */
    private readonly persistedById = computed(() => {
        const m = new Map<string, ReconBreak>();
        for (const b of this.recon()?.breaks ?? []) m.set(breakId(b), b);
        return m;
    });

    /** Live breaks with the persisted lifecycle overlaid. */
    readonly drillBreaks = computed<ReconBreak[]>(() => {
        const live = this.liveBreaks() ?? [];
        const persisted = this.persistedById();
        return live.map((b) => {
            const p = persisted.get(breakId(b));
            return p && p.status !== 'auto_closed' ? { ...b, status: p.status, note: p.note } : b;
        });
    });

    readonly missingA = computed(() => this.drillBreaks().filter((b) => b.type === 'missing_right'));
    readonly missingB = computed(() => this.drillBreaks().filter((b) => b.type === 'missing_left'));
    readonly valueBreaks = computed(() => this.drillBreaks().filter((b) => b.type === 'value_break'));
    readonly resolvedCount = computed(() => this.drillBreaks().filter((b) => b.status === 'resolved').length);

    /** Key + status (+ actions) — the shape of the two missing-side tables. */
    readonly missingColumns: ColDef<ReconBreak>[] = [
        { field: 'key', headerName: 'Key', flex: 1 },
        {
            field: 'status', headerName: 'Status', width: 130,
            cellRenderer: (p: ICellRendererParams<ReconBreak>) => statusBadgeHtml(p.value as string),
        },
    ];

    readonly valueColumns = computed<ColDef<ReconBreak>[]>(() => {
        const r = this.recon();
        return [
            { field: 'key', headerName: 'Key', flex: 1 },
            { field: 'column', headerName: 'Column', width: 140 },
            { field: 'leftValue', headerName: r?.leftDataset || 'A', width: 140, valueFormatter: (p) => fmtVal(p.value) },
            { field: 'rightValue', headerName: r?.rightDataset || 'B', width: 140, valueFormatter: (p) => fmtVal(p.value) },
            { field: 'diff', headerName: 'Δ', width: 120, cellRenderer: varianceCell() },
            {
                field: 'status', headerName: 'Status', width: 130,
                cellRenderer: (p: ICellRendererParams<ReconBreak>) => statusBadgeHtml(p.value as string),
            },
        ];
    });

    readonly rowActions: InspectoRowAction<ReconBreak>[] = [
        {
            icon: (b) => (b.status === 'resolved' ? 'heroicons_outline:arrow-uturn-left' : 'heroicons_outline:check'),
            hint: (b) => (b.status === 'resolved' ? 'Re-open' : 'Resolve'),
            onClick: (b) => this.toggleResolve(b),
        },
    ];

    // ── grouped (tree-table) toggle: breaks grouped by type, aligned columns + Δ ────────
    private readonly breaksById = computed(() => {
        const m = new Map<string, ReconBreak>();
        for (const b of this.drillBreaks()) m.set(breakId(b), b);
        return m;
    });

    readonly treeNodes = computed<TreeNode[]>(() => {
        const groups = new Map<string, ReconBreak[]>();
        for (const b of this.drillBreaks()) {
            const g = groups.get(b.type) ?? [];
            g.push(b);
            groups.set(b.type, g);
        }
        const out: TreeNode[] = [];
        for (const [type, list] of groups) {
            const sumDiff = list.reduce((s, b) => s + (typeof b.diff === 'number' ? b.diff : 0), 0);
            out.push({
                id: `grp:${type}`,
                label: `${breakLabel(type)} (${list.length})`,
                icon: 'heroicons_outline:rectangle-stack',
                expanded: true,
                values: { diff: sumDiff || undefined },
                children: list.map((b) => ({
                    id: breakId(b),
                    label: b.key,
                    values: {
                        column: b.column ?? '—',
                        leftValue: b.leftValue,
                        rightValue: b.rightValue,
                        diff: b.diff,
                        status: b.status,
                    },
                })),
            });
        }
        return out;
    });

    readonly treeColumns = computed<ColDef[]>(() => {
        const r = this.recon();
        return [
            { field: 'column', headerName: 'Column', width: 150, valueFormatter: (p) => p.value ?? '—' },
            { field: 'leftValue', headerName: r?.leftDataset || 'Left', flex: 1, valueFormatter: (p) => fmtVal(p.value) },
            { field: 'rightValue', headerName: r?.rightDataset || 'Right', flex: 1, valueFormatter: (p) => fmtVal(p.value) },
            { field: 'diff', headerName: 'Δ', width: 120, cellRenderer: varianceCell() },
            {
                field: 'status', headerName: 'Status', width: 120,
                cellRenderer: (p: ICellRendererParams) => (p.value ? statusBadgeHtml(String(p.value)) : ''),
            },
        ];
    });

    readonly treeActions: InspectoRowAction<FlatTreeRow>[] = [
        {
            icon: (row) => (this.breakOf(row)?.status === 'resolved' ? 'heroicons_outline:arrow-uturn-left' : 'heroicons_outline:check'),
            hint: (row) => (this.breakOf(row)?.status === 'resolved' ? 'Re-open' : 'Resolve'),
            visible: (row) => !!this.breakOf(row),
            onClick: (row) => {
                const b = this.breakOf(row);
                if (b) void this.toggleResolve(b);
            },
        },
    ];

    private breakOf(row: FlatTreeRow): ReconBreak | undefined {
        return this.breaksById().get(row.__id);
    }

    ngOnInit(): void {
        const id = this.route.snapshot.paramMap.get('id') ?? '';
        this.path.set(decodePath(this.route.snapshot.queryParamMap.get('path')));
        this.api.get(id).subscribe({
            next: (r) => {
                this.recon.set(r);
                this.loading.set(false);
                void this.compute();
            },
            error: (e) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, `Could not load reconciliation "${id}"`));
            },
        });
    }

    /** (Re)compute the live break sets for the current scope. */
    async compute(): Promise<void> {
        const r = this.recon();
        if (!r || this.computing()) return;
        this.computing.set(true);
        try {
            this.liveBreaks.set(breaksFromSets(r, await this.exec.breaks(r, this.path())));
        } catch (e) {
            this.liveBreaks.set(null);
            this.toastr.error(apiErrorMessage(e, 'Could not compute the break sets'));
        } finally {
            this.computing.set(false);
        }
    }

    /** Resolve / re-open one break — persisted by identity (a fresh live break is appended on first touch). */
    async toggleResolve(b: ReconBreak): Promise<void> {
        const r = this.recon();
        if (!r) return;
        const resolving = b.status !== 'resolved';
        if (resolving && !(await this.confirm.confirm(`Mark this ${breakLabel(b.type)} for key "${b.key}" resolved?`, 'Resolve break'))) return;
        const known = this.persistedById().has(breakId(b));
        const breaks = known
            ? resolveBreak(r.breaks, b, resolving)
            : [...r.breaks, { ...b, status: resolving ? ('resolved' as const) : ('open' as const) }];
        const updated: Reconciliation = { ...r, breaks };
        this.api.save(updated).subscribe({
            next: () => this.recon.set(updated),
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not update the break')),
        });
    }
}

function breakLabel(type: string): string {
    return type === 'missing_left' ? 'missing left' : type === 'missing_right' ? 'missing right' : type === 'value_break' ? 'value break' : type;
}
function fmtVal(v: unknown): string {
    if (v == null) return '—';
    const n = Number(v);
    return Number.isNaN(n) ? String(v) : n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
