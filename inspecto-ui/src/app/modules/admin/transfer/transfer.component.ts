import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { Observable, forkJoin, from, of } from 'rxjs';
import { catchError, concatMap, map, toArray } from 'rxjs/operators';
import { ToastrService } from 'ngx-toastr';
import {
    ComponentType,
    ComponentsService,
    ConnectionProfile,
    ConnectionsService,
    LensService,
    PipelinesService,
    SpacesService,
    apiErrorMessage,
} from 'app/inspecto/api';
import type { AuthoredPipeline } from 'app/inspecto/api/pipelines.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import {
    BUNDLE_KINDS,
    BundleItem,
    BundleKind,
    ImportRow,
    MetadataBundle,
    buildBundle,
    parseBundle,
    planImport,
    withDependencies,
} from './bundle';

/** An import-preview row + its post-apply outcome. */
interface Row extends ImportRow {
    result?: 'imported' | 'overwritten' | 'failed';
    message?: string;
}

const COMPONENT_KINDS = BUNDLE_KINDS.map((k) => k.kind).filter(
    (k): k is Extract<BundleKind, ComponentType> => k !== 'connection' && k !== 'authored-pipeline',
);

/**
 * Settings **Import & Export** — cross-instance Metadata Bundles (staging → production promotion).
 * Export: pick artifacts (datasets/widgets/dashboards/saved views/pipelines/registry pieces),
 * optionally expanded to their dependency closure, downloaded as one JSON bundle — **metadata only,
 * never data rows**. Import: upload a bundle, preview new-vs-existing per artifact, choose
 * overwrite/skip per item (datasets especially), apply in reference order. Pure logic in
 * `bundle.ts`; format doc: `docs/superpower/metadata-bundle.md`.
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
    private components = inject(ComponentsService);
    private connections = inject(ConnectionsService);
    private pipelines = inject(PipelinesService);
    private spaces = inject(SpacesService);
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
        const componentLists = Object.fromEntries(
            COMPONENT_KINDS.map((kind) => [kind, this.components.list(kind).pipe(catchError(() => of([])))]),
        );
        forkJoin({
            ...componentLists,
            connection: this.connections.list().pipe(catchError(() => of([]))),
            pipelineNames: this.pipelines.authoredList().pipe(
                map((list) => list.map((p) => p.name)),
                catchError(() => of([] as string[])),
            ),
        })
            .pipe(
                concatMap((res) => {
                    const raws = (res.pipelineNames as string[]).map((name) =>
                        this.pipelines.authoredRaw(name).pipe(catchError(() => of(null))),
                    );
                    return (raws.length ? forkJoin(raws) : of([])).pipe(map((pipelines) => ({ res, pipelines })));
                }),
            )
            .subscribe(({ res, pipelines }) => {
                const items: BundleItem[] = [];
                for (const kind of COMPONENT_KINDS) {
                    for (const def of res[kind] as { name: string; content: Record<string, unknown> }[]) {
                        items.push({ kind, id: def.name, content: def.content });
                    }
                }
                for (const c of res.connection as ConnectionProfile[]) {
                    items.push({ kind: 'connection', id: c.id, content: c as unknown as Record<string, unknown> });
                }
                for (const p of pipelines) {
                    if (p) items.push({ kind: 'authored-pipeline', id: p.name, content: p as unknown as Record<string, unknown> });
                }
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
        let items = selected;
        let missing: string[] = [];
        if (this.includeDeps()) ({ items, missing } = withDependencies(selected, all));
        const space = this.spaces.currentSpaceId();
        const bundle = buildBundle(items, space);
        const stamp = bundle.exportedAt.slice(0, 16).replace(/[:T]/g, '-');
        this.download(`inspecto-bundle-${space ?? 'default'}-${stamp}.json`, JSON.stringify(bundle, null, 2));
        this.toastr.success(`Exported ${items.length} artifact(s)${items.length > selected.length ? ` (${items.length - selected.length} pulled in as dependencies)` : ''}`);
        if (missing.length) this.toastr.warning(`Unresolved references left out: ${missing.join(', ')}`);
    }

    private download(name: string, text: string): void {
        const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = name;
        link.click();
        URL.revokeObjectURL(url);
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
        this.rows.set(bundle ? planImport(bundle, this.existingIds()) : []);
    }

    private existingIds(): Map<BundleKind, Set<string>> {
        const m = new Map<BundleKind, Set<string>>();
        for (const i of this.allItems()) {
            if (!m.has(i.kind)) m.set(i.kind, new Set());
            m.get(i.kind)!.add(i.id);
        }
        return m;
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
                    this.write(row).pipe(
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

    private write(row: Row): Observable<unknown> {
        const { kind, id, content } = row.item;
        const overwrite = row.action === 'overwrite';
        if (kind === 'connection') {
            const profile = { ...(content as unknown as ConnectionProfile), id };
            return overwrite ? this.connections.update(id, profile) : this.connections.create(profile);
        }
        if (kind === 'authored-pipeline') {
            const pipeline = { ...(content as unknown as AuthoredPipeline), name: id };
            return overwrite ? this.pipelines.replaceAuthored(id, pipeline) : this.pipelines.createAuthored(pipeline);
        }
        return overwrite ? this.components.update(kind, id, content) : this.components.create(kind, { id, ...content });
    }

    kindLabel(kind: BundleKind): string {
        return this.kinds.find((k) => k.kind === kind)?.label ?? kind;
    }
}
