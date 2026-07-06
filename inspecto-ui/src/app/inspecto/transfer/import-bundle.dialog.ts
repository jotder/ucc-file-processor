import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { from, of } from 'rxjs';
import { catchError, concatMap, map, toArray } from 'rxjs/operators';
import { ToastrService } from 'ngx-toastr';
import { LensService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { BundleTransferService } from './bundle-transfer.service';
import { BUNDLE_KINDS, BundleKind, ImportRow, MetadataBundle, RequireStatus, TargetIndex, parseBundle, planImport, resolveRequires, targetIndex } from './bundle';

export interface ImportBundleData {
    /** When set, only these kind names are importable here (a library scopes its import); absent = all. */
    allowedKinds?: string[];
    title?: string;
}

interface Row extends ImportRow {
    result?: 'imported' | 'overwritten' | 'failed';
    message?: string;
}

/**
 * The shared Metadata-Bundle import preview + apply (R6) — one component behind every import surface
 * (Settings, library toolbars, editor menus). Loads the target's artifacts, fit-checks an uploaded
 * bundle (new / exists / drifted per item + a satisfied/missing `requires` panel), lets the user pick
 * import/overwrite/skip per row, and applies through {@link BundleTransferService}. Closes with the
 * number of artifacts written so the host can reload.
 */
@Component({
    selector: 'app-import-bundle-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatSelectModule, MatIconModule, MatProgressSpinnerModule, InspectoAlertComponent, InspectoEmptyStateComponent, StatusBadgeComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './import-bundle.dialog.html',
})
export class ImportBundleDialog {
    private transfer = inject(BundleTransferService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ImportBundleDialog>);
    readonly data = inject<ImportBundleData>(MAT_DIALOG_DATA) ?? {};
    readonly lens = inject(LensService);

    readonly loading = signal(true);
    readonly fileName = signal<string | null>(null);
    readonly parseErrors = signal<string[]>([]);
    readonly rows = signal<Row[]>([]);
    readonly requires = signal<RequireStatus[]>([]);
    readonly applying = signal(false);
    readonly applied = signal(false);
    readonly importedCount = signal(0);

    private target = signal<TargetIndex>(new Map());

    readonly existingCount = computed(() => this.rows().filter((r) => r.exists).length);
    readonly driftedCount = computed(() => this.rows().filter((r) => r.drifted).length);
    readonly actionableCount = computed(() => this.rows().filter((r) => r.action !== 'skip').length);
    readonly missingRequires = computed(() => this.requires().filter((r) => r.status === 'missing'));
    readonly bundleHasConnections = computed(() => this.rows().some((r) => r.item.kind === 'connection'));

    constructor() {
        this.transfer.loadAll().subscribe((items) => {
            this.target.set(targetIndex(items));
            this.loading.set(false);
        });
    }

    async onFile(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        this.fileName.set(file.name);
        this.applied.set(false);
        const { bundle, errors } = parseBundle(await file.text());
        this.parseErrors.set(errors);
        this.requires.set(bundle ? resolveRequires(bundle, this.target()) : []);
        this.rows.set(bundle ? this.filtered(bundle) : []);
    }

    private filtered(bundle: MetadataBundle): Row[] {
        const allowed = this.data.allowedKinds;
        return planImport(bundle, this.target()).filter((r) => !allowed || allowed.includes(r.item.kind));
    }

    setAction(row: Row, action: Row['action']): void {
        this.rows.update((rows) => rows.map((r) => (r === row ? { ...r, action } : r)));
    }

    overwriteAllExisting(): void {
        this.rows.update((rows) => rows.map((r) => (r.exists ? { ...r, action: 'overwrite' } : r)));
    }

    skipAllExisting(): void {
        this.rows.update((rows) => rows.map((r) => (r.exists ? { ...r, action: 'skip' } : r)));
    }

    kindLabel(kind: BundleKind): string {
        return BUNDLE_KINDS.find((k) => k.kind === kind)?.label ?? kind;
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
                this.importedCount.set(outcomes.length - failed);
                if (failed) this.toastr.warning(`Imported ${outcomes.length - failed} artifact(s); ${failed} failed — see the result column.`);
                else this.toastr.success(`Imported ${outcomes.length} artifact(s)`);
            });
    }

    close(): void {
        this.ref.close(this.importedCount());
    }
}
