import { ChangeDetectionStrategy, Component, ViewEncapsulation, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    CheckOutcome,
    ConnectionProbeResult,
    ConnectionProbeService,
    ConnectionProfile,
    ConnectionsService,
    ResourceNode,
    SampleResult,
} from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { ConnectionTreeComponent } from './connection-tree.component';

/**
 * Connection workbench — the four-verb detail view for one connection profile (connect · explore · test ·
 * sample), DBeaver-style. Test = a graded probe (per-check reachability/auth/read/write/list); Explore = a
 * lazy resource tree; Sample = a bounded preview grid of the selected file/table. Reads the (mocked-now,
 * library-later) `ConnectionProbeService`; reuses the shared status-badge / alert / empty-state / grid.
 */
@Component({
    selector: 'app-connection-workbench',
    standalone: true,
    imports: [
        RouterLink,
        MatButtonModule,
        MatButtonToggleModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        DataTableComponent,
        StatusBadgeComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        ConnectionTreeComponent,
    ],
    templateUrl: './connection-workbench.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class ConnectionWorkbenchComponent {
    private route = inject(ActivatedRoute);
    private connections = inject(ConnectionsService);
    private probeApi = inject(ConnectionProbeService);
    private toastr = inject(ToastrService);

    readonly id = this.route.snapshot.paramMap.get('id') ?? '';
    readonly connection = signal<ConnectionProfile | null>(null);

    // Test (graded probe)
    readonly probing = signal(false);
    readonly probe = signal<ConnectionProbeResult | null>(null);
    readonly lastTested = signal<Date | null>(null);
    readonly probeSummary = computed(() => {
        const p = this.probe();
        if (!p) return null;
        return {
            passed: p.checks.filter((c) => !c.skipped && c.ok).length,
            skipped: p.checks.filter((c) => c.skipped).length,
            failed: p.checks.filter((c) => !c.skipped && !c.ok).length,
        };
    });
    /** Header status pill: Testing… while probing, then Untested → neutral, Connected → success, Failed → error. */
    readonly statusChip = computed(() => {
        if (this.probing()) return { value: 'PENDING', label: 'Testing…' };
        const p = this.probe();
        if (!p) return { value: 'UNTESTED', label: 'Untested' };
        return p.ok ? { value: 'HEALTHY', label: 'Connected' } : { value: 'FAILED', label: 'Failed' };
    });

    // Explore (lazy resource tree)
    readonly exploring = signal(false);
    readonly rootNodes = signal<ResourceNode[]>([]);
    readonly childrenByPath = signal<Record<string, ResourceNode[]>>({});
    readonly expanded = signal<Set<string>>(new Set<string>());
    readonly loadingPaths = signal<Set<string>>(new Set<string>());
    readonly treeFilter = signal('');

    // Sample (preview grid)
    readonly selected = signal<ResourceNode | null>(null);
    readonly sampling = signal(false);
    readonly sample = signal<SampleResult | null>(null);
    readonly sampleLimit = signal(50);
    readonly limitOptions = [50, 100, 500];

    readonly sampleCols = computed<ColDef[]>(() => (this.sample()?.columns ?? []).map((c) => ({ field: c })));
    /** Descriptive CSV filename for the data-table's export action (mirrors the old hand-rolled download). */
    readonly exportName = computed<string>(() => {
        const s = this.sample();
        return s ? `${this.id}_${s.path.replace(/[^\w.-]+/g, '_')}_sample` : `${this.id}_sample`;
    });

    constructor() {
        if (this.id) {
            this.connections.get(this.id).subscribe({ next: (c) => this.connection.set(c), error: () => undefined });
            this.loadRoot();
            this.runProbe(); // auto-probe on open so connection status is immediate
        }
    }

    // ── Test ────────────────────────────────────────────────────────────────
    runProbe(): void {
        if (!this.id) return;
        this.probing.set(true);
        this.probeApi.probe(this.id, { sampleLimit: 5 }).subscribe({
            next: (r) => {
                this.probe.set(r);
                this.lastTested.set(new Date());
                this.probing.set(false);
            },
            error: (e) => {
                this.probing.set(false);
                this.toastr.warning(apiErrorMessage(e, `Probe failed for ${this.id}`));
            },
        });
    }

    /** Status token for one check → maps to a status-badge tone (OK→success, FAILED→error, SKIPPED→neutral). */
    checkStatus(c: CheckOutcome): string {
        return c.skipped ? 'SKIPPED' : c.ok ? 'OK' : 'FAILED';
    }

    // ── Explore ─────────────────────────────────────────────────────────────
    private loadRoot(): void {
        this.exploring.set(true);
        this.probeApi.explore(this.id).subscribe({
            next: (nodes) => {
                this.rootNodes.set(nodes);
                this.exploring.set(false);
            },
            error: (e) => {
                this.exploring.set(false);
                this.toastr.warning(apiErrorMessage(e, `Could not explore ${this.id}`));
            },
        });
    }

    onExpand(node: ResourceNode): void {
        if (!node.hasChildren) return;
        const open = new Set(this.expanded());
        if (open.has(node.path)) {
            open.delete(node.path);
            this.expanded.set(open);
            return;
        }
        open.add(node.path);
        this.expanded.set(open);
        if (this.childrenByPath()[node.path]) return; // already loaded
        this.setLoading(node.path, true);
        this.probeApi.explore(this.id, node.path).subscribe({
            next: (children) => {
                this.childrenByPath.set({ ...this.childrenByPath(), [node.path]: children });
                this.setLoading(node.path, false);
            },
            error: (e) => {
                this.setLoading(node.path, false);
                this.toastr.warning(apiErrorMessage(e, `Could not expand ${node.name}`));
            },
        });
    }

    private setLoading(path: string, on: boolean): void {
        const next = new Set(this.loadingPaths());
        if (on) next.add(path);
        else next.delete(path);
        this.loadingPaths.set(next);
    }

    onSelect(node: ResourceNode): void {
        this.selected.set(node);
        if (node.kind === 'file' || node.kind === 'table') {
            this.loadSample(node);
        } else if (node.hasChildren) {
            this.onExpand(node);
        }
    }

    onTreeFilter(ev: Event): void {
        this.treeFilter.set((ev.target as HTMLInputElement).value);
    }

    // ── Sample ──────────────────────────────────────────────────────────────
    /** Build the current sample as CSV (RFC-4180 escaping). Client-side only — never round-trips. */
    private sampleCsv(): string {
        const s = this.sample();
        if (!s) return '';
        const esc = (v: unknown) => {
            const str = v == null ? '' : String(v);
            return /[",\n]/.test(str) ? '"' + str.replace(/"/g, '""') + '"' : str;
        };
        const lines = [s.columns.map(esc).join(','), ...s.rows.map((r) => s.columns.map((c) => esc(r[c])).join(','))];
        return lines.join('\n');
    }

    copyCsv(): void {
        navigator.clipboard?.writeText(this.sampleCsv()).then(
            () => this.toastr.success('Sample copied as CSV'),
            () => this.toastr.warning('Clipboard unavailable'),
        );
    }

    /** Change the row cap; re-sample the current node if one is selected. */
    setLimit(n: number): void {
        if (this.sampleLimit() === n) return;
        this.sampleLimit.set(n);
        const sel = this.selected();
        if (sel && (sel.kind === 'file' || sel.kind === 'table')) this.loadSample(sel);
    }

    private loadSample(node: ResourceNode): void {
        this.sampling.set(true);
        this.sample.set(null);
        this.probeApi.sample(this.id, node.path, this.sampleLimit()).subscribe({
            next: (s) => {
                this.sample.set(s);
                this.sampling.set(false);
            },
            error: (e) => {
                this.sampling.set(false);
                this.toastr.warning(apiErrorMessage(e, `Could not sample ${node.name}`));
            },
        });
    }
}
