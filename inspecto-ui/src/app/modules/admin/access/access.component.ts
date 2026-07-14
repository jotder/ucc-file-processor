import {
    ChangeDetectionStrategy,
    Component,
    computed,
    effect,
    inject,
    signal,
    viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ColDef } from 'ag-grid-community';
import { cloneDeep } from 'lodash-es';
import { ToastrService } from 'ngx-toastr';
import { forkJoin, Observable } from 'rxjs';

import { AccessStateService } from 'app/inspecto/access/access-state.service';
import {
    CatalogIndex,
    deriveDefaultAccessCatalog,
    indexCatalog,
    resolveGrant,
} from 'app/inspecto/access/access-catalog';
import {
    AccessCatalog,
    AccessGrant,
    AccessNode,
    AccessService,
    apiErrorMessage,
    LensService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { TreeTableComponent } from 'app/inspecto/tree-table/tree-table.component';
import { TreeNode } from 'app/inspecto/tree-table/tree-types';
import { AccessCellState, AccessGrantCell } from './access-grant-cell.component';

/** Per-lens working grants (lens id → sparse nodeId → allow|deny). */
type GrantsBySubject = Record<string, Record<string, AccessGrant>>;

/**
 * Settings ▸ Access — the lens access matrix (design `docs/superpower/lens-access-config-design.md`
 * §6): rows = the Access Catalog (menus → panes → functionalities, derived live from the platform
 * navigation), one column per Lens, each cell cycling Inherit → Hidden → Shown. Saving snapshots the
 * derived catalog and the dirty lens profiles to `/access/*`; `AccessStateService.reload()` then
 * applies them immediately (sidebar filter + capability re-derivation). Everything defaults to Shown —
 * an empty configuration reproduces today's behavior exactly.
 */
@Component({
    selector: 'app-access',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        InspectoAlertComponent,
        TreeTableComponent,
    ],
    templateUrl: './access.component.html',
})
export class AccessComponent {
    private readonly api = inject(AccessService);
    private readonly lens = inject(LensService);
    private readonly accessState = inject(AccessStateService);
    private readonly toastr = inject(ToastrService);

    /** Display order mirrors the "View as" switcher, so the matrix reads like the header menu. */
    readonly subjects = LensService.LENSES;

    /** The catalog is derived fresh from the live navigation — the UI is its source of truth (§4). */
    private readonly catalogNodes: AccessNode[] = deriveDefaultAccessCatalog();
    private readonly idx: CatalogIndex = indexCatalog(this.catalogNodes);

    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly search = signal('');
    private readonly savedVersion = signal(0);
    private readonly grants = signal<GrantsBySubject>(this.emptyGrants());
    private readonly baseline = signal<GrantsBySubject>(this.emptyGrants());

    readonly canEdit = computed(() => this.lens.canConfigureAccess());
    readonly dirty = computed(() => JSON.stringify(this.grants()) !== JSON.stringify(this.baseline()));

    /** "n hidden" per lens — the at-a-glance over-locked-lens check in the legend strip. */
    readonly hiddenCounts = computed<Record<string, number>>(() => {
        const grants = this.grants();
        const counts: Record<string, number> = {};
        for (const s of this.subjects) {
            let n = 0;
            for (const id of this.idx.byId.keys()) {
                if (resolveGrant(id, grants[s.id] ?? {}, this.idx).effective === 'deny') n++;
            }
            counts[s.id] = n;
        }
        return counts;
    });

    /** The matrix rows: the (search-filtered) catalog with per-lens display values (CSV-exportable). */
    readonly nodes = computed<TreeNode[]>(() =>
        this.toTree(this.filterCatalog(this.catalogNodes, this.search().trim().toLowerCase())));

    /** One column per lens; the cells read/write the working grants through the callbacks. */
    readonly columns: ColDef[] = this.subjects.map((s) => ({
        colId: s.id,
        field: s.id,
        headerName: s.label,
        minWidth: 140,
        sortable: false,
        cellRenderer: AccessGrantCell,
        cellRendererParams: {
            subject: s.id,
            subjectLabel: s.label,
            state: (nodeId: string, subject: string): AccessCellState => this.cellState(nodeId, subject),
            cycle: (nodeId: string, subject: string): void => this.cycle(nodeId, subject),
        },
    }));

    private readonly tree = viewChild(TreeTableComponent);

    constructor() {
        this.load();
        // While searching, everything expands so matches are never hidden inside a collapsed group.
        effect(() => {
            if (this.search().trim()) this.tree()?.expandAll();
        });
    }

    onSearch(value: string): void {
        this.search.set(value);
    }

    /** Inherit → Hidden → Shown → Inherit (most edits start as "hide this for that lens"). */
    cycle(nodeId: string, subject: string): void {
        if (!this.canEdit() || this.saving()) return;
        this.grants.update((all) => {
            const mine = { ...(all[subject] ?? {}) };
            const current = mine[nodeId];
            if (!current) mine[nodeId] = 'deny';
            else if (current === 'deny') mine[nodeId] = 'allow';
            else delete mine[nodeId];
            return { ...all, [subject]: mine };
        });
    }

    /** Clear every explicit grant (back to all-Shown). Takes effect on Save; Discard undoes it. */
    resetAll(): void {
        this.grants.set(this.emptyGrants());
    }

    discard(): void {
        this.grants.set(cloneDeep(this.baseline()));
    }

    save(): void {
        if (!this.dirty() || this.saving()) return;
        this.saving.set(true);
        const catalog: AccessCatalog = { version: this.savedVersion() + 1, nodes: this.catalogNodes };
        const puts: Observable<unknown>[] = [this.api.saveCatalog(catalog)];
        for (const s of this.subjects) {
            const changed = JSON.stringify(this.grants()[s.id] ?? {}) !== JSON.stringify(this.baseline()[s.id] ?? {});
            if (changed) {
                puts.push(this.api.saveProfile({
                    subjectType: 'lens',
                    subjectId: s.id,
                    label: s.label,
                    grants: this.grants()[s.id] ?? {},
                }));
            }
        }
        forkJoin(puts).subscribe({
            next: () => {
                this.savedVersion.update((v) => v + 1);
                this.baseline.set(cloneDeep(this.grants()));
                this.saving.set(false);
                this.toastr.success('Access configuration saved — switch lens via "View as" to preview it');
                this.accessState.reload();   // apply live: sidebar filter + capability re-derivation
            },
            error: (err) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(err, 'Could not save the access configuration'));
            },
        });
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private load(): void {
        forkJoin({ catalog: this.api.catalog(), profiles: this.api.profiles() }).subscribe({
            next: ({ catalog, profiles }) => {
                this.savedVersion.set(catalog?.version ?? 0);
                const grants = this.emptyGrants();
                for (const p of profiles ?? []) {
                    if (p.subjectType === 'lens' && grants[p.subjectId]) grants[p.subjectId] = p.grants ?? {};
                }
                this.baseline.set(grants);
                this.grants.set(cloneDeep(grants));
                this.loading.set(false);
            },
            error: () => {
                // Unreachable/read-only backend: start from the all-Shown default; Save will surface it.
                this.loading.set(false);
            },
        });
    }

    private emptyGrants(): GrantsBySubject {
        return Object.fromEntries(this.subjects.map((s) => [s.id, {}]));
    }

    private cellState(nodeId: string, subject: string): AccessCellState {
        const st = resolveGrant(nodeId, this.grants()[subject] ?? {}, this.idx);
        return {
            effective: st.effective,
            explicit: st.explicit,
            sourceLabel: st.explicit ? null : st.sourceLabel,
            editable: this.canEdit() && !this.saving(),
        };
    }

    private toTree(nodes: AccessNode[]): TreeNode[] {
        const grants = this.grants();
        return nodes.map((n) => {
            const values: Record<string, unknown> = {};
            for (const s of this.subjects) {
                const st = resolveGrant(n.id, grants[s.id] ?? {}, this.idx);
                const word = st.effective === 'allow' ? 'Shown' : 'Hidden';
                values[s.id] = st.explicit ? word : `${word} (inherited)`;
            }
            return {
                id: n.id,
                label: n.label,
                icon: n.icon,
                values,
                children: n.children?.length ? this.toTree(n.children) : undefined,
            };
        });
    }

    /** Label filter keeping whole matched subtrees and the ancestors of deeper matches. */
    private filterCatalog(nodes: AccessNode[], term: string): AccessNode[] {
        if (!term) return nodes;
        const out: AccessNode[] = [];
        for (const n of nodes) {
            if (n.label.toLowerCase().includes(term)) {
                out.push(n);
                continue;
            }
            const children = n.children?.length ? this.filterCatalog(n.children, term) : [];
            if (children.length) out.push({ ...n, children });
        }
        return out;
    }
}
