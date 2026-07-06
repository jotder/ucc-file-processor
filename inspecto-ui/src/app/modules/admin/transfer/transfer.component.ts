import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { from, of } from 'rxjs';
import { catchError, concatMap, map, toArray } from 'rxjs/operators';
import { ToastrService } from 'ngx-toastr';
import { LensService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import {
    BUNDLE_KINDS,
    BundleItem,
    BundleKind,
    BundleTransferService,
    ImportRow,
    MetadataBundle,
    TargetIndex,
    parseBundle,
    planImport,
    targetIndex,
} from 'app/inspecto/transfer';

/** An import-preview row + its post-apply outcome. */
interface Row extends ImportRow {
    result?: 'imported' | 'overwritten' | 'failed';
    message?: string;
}

/**
 * Settings **Import & Export** — cross-instance Metadata Bundles (staging → production promotion).
 * Export: pick artifacts (datasets/widgets/dashboards/saved views/pipelines/registry pieces),
 * optionally expanded to their dependency closure, downloaded as one JSON bundle — **metadata only,
 * never data rows**. Import: upload a bundle, preview new-vs-existing (and drifted) per artifact,
 * choose overwrite/skip per item, apply in reference order. Load/write/export are single-sourced in
 * {@link BundleTransferService} (shared with the per-surface `<inspecto-transfer-menu>`); pure format
 * logic in `inspecto/transfer/bundle.ts`; doc: `docs/superpower/metadata-bundle.md`.
 */
@Component({
    selector: 'app-transfer',
    standalone: true,
    imports: [
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTabsModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './transfer.component.html',
})
export class TransferComponent implements OnInit {
    private transfer = inject(BundleTransferService);
    private toastr = inject(ToastrService);
    readonly lens = inject(LensService);

    readonly kinds = BUNDLE_KINDS;
    readonly loading = signal(false);
    /** Everything exportable on this instance (also the import target's existing ids). */
    readonly allItems = signal<BundleItem[]>([]);
    readonly selection = signal<Set<string>>(new Set());
    readonly includeDeps = signal(true);

    // ── import state ──
    readonly fileName = signal<string | null>(null);
    readonly parseErrors = signal<string[]>([]);
    readonly bundle = signal<MetadataBundle | null>(null);
    readonly rows = signal<Row[]>([]);
    readonly applying = signal(false);
    readonly applied = signal(false);

    readonly groups = computed(() =>
        this.kinds
            .map(({ kind, label }) => ({ kind, label, items: this.allItems().filter((i) => i.kind === kind) }))
            .filter((g) => g.items.length > 0),
    );
    readonly selectedCount = computed(() => this.selection().size);
    readonly existingCount = computed(() => this.rows().filter((r) => r.exists).length);
    readonly actionableCount = computed(() => this.rows().filter((r) => r.action !== 'skip').length);
    readonly bundleHasConnections = computed(() => this.rows().some((r) => r.item.kind === 'connection'));

    ngOnInit(): void {
        this.load();
    }

    /** Load every exportable artifact (component kinds + connections + lossless pipelines). */
    load(): void {
        this.loading.set(true);
        this.transfer.loadAll().subscribe((items) => {
            this.allItems.set(items);
            this.loading.set(false);
        });
    }

    // ── export ──

    itemKey(i: BundleItem): string {
        return `${i.kind}/${i.id}`;
    }

    isSelected(i: BundleItem): boolean {
        return this.selection().has(this.itemKey(i));
    }

    toggleItem(i: BundleItem): void {
        const next = new Set(this.selection());
        const k = this.itemKey(i);
        if (next.has(k)) next.delete(k);
        else next.add(k);
        this.selection.set(next);
    }

    kindAllSelected(kind: BundleKind): boolean {
        const items = this.allItems().filter((i) => i.kind === kind);
        return items.length > 0 && items.every((i) => this.isSelected(i));
    }

    toggleKind(kind: BundleKind): void {
        const next = new Set(this.selection());
        const items = this.allItems().filter((i) => i.kind === kind);
        const all = this.kindAllSelected(kind);
        for (const i of items) {
            if (all) next.delete(this.itemKey(i));
            else next.add(this.itemKey(i));
        }
        this.selection.set(next);
    }

    exportBundle(): void {
        const all = this.allItems();
        const selected = all.filter((i) => this.isSelected(i));
        if (!selected.length) return;
        const { bundle, missing } = this.transfer.buildExport(selected, all, this.includeDeps());
        this.transfer.download(bundle);
        const extra = bundle.items.length - selected.length;
        this.toastr.success(`Exported ${bundle.items.length} artifact(s)${extra > 0 ? ` (${extra} pulled in as dependencies)` : ''}`);
        if (missing.length) this.toastr.warning(`Unresolved references left out: ${missing.join(', ')}`);
    }

    // ── import ──

    async onFile(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        this.fileName.set(file.name);
        this.applied.set(false);
        const { bundle, errors } = parseBundle(await file.text());
        this.parseErrors.set(errors);
        this.bundle.set(bundle ?? null);
        this.rows.set(bundle ? planImport(bundle, this.targetIndex()) : []);
    }

    private targetIndex(): TargetIndex {
        return targetIndex(this.allItems());
    }

    setAction(row: Row, action: Row['action']): void {
        this.rows.update((rows) => rows.map((r) => (r === row ? { ...r, action } : r)));
    }

    /** The user's bulk lever — flip every already-existing artifact (datasets included) to overwrite. */
    overwriteAllExisting(): void {
        this.rows.update((rows) => rows.map((r) => (r.exists ? { ...r, action: 'overwrite' } : r)));
    }

    skipAllExisting(): void {
        this.rows.update((rows) => rows.map((r) => (r.exists ? { ...r, action: 'skip' } : r)));
    }

    apply(): void {
        if (!this.lens.canAuthorWorkbench() || this.applying()) return;
        const work = this.rows().filter((r) => r.action !== 'skip');
        if (!work.length) return;
        this.applying.set(true);
        from(work)
            .pipe(
                concatMap((row) =>
                    this.transfer.write(row.item, row.action === 'overwrite').pipe(
                        map(() => ({ row, result: (row.action === 'overwrite' ? 'overwritten' : 'imported') as Row['result'] })),
                        catchError((err) => of({ row, result: 'failed' as Row['result'], message: apiErrorMessage(err, 'Write failed.') })),
                    ),
                ),
                toArray(),
            )
            .subscribe((outcomes) => {
                this.rows.update((rows) =>
                    rows.map((r) => {
                        const o = outcomes.find((x) => x.row === r);
                        return o ? { ...r, result: o.result, message: 'message' in o ? o.message : undefined } : r;
                    }),
                );
                this.applying.set(false);
                this.applied.set(true);
                const failed = outcomes.filter((o) => o.result === 'failed').length;
                if (failed) this.toastr.warning(`Imported ${outcomes.length - failed} artifact(s); ${failed} failed — see the result column.`);
                else this.toastr.success(`Imported ${outcomes.length} artifact(s)`);
                this.load(); // refresh target ids so a re-run of the preview reflects the writes
            });
    }

    kindLabel(kind: BundleKind): string {
        return this.kinds.find((k) => k.kind === kind)?.label ?? kind;
    }
}
