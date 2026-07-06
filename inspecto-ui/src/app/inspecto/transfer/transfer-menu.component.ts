import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { LensService } from 'app/inspecto/api';
import { BundleItem, BundleKind } from './bundle';
import { BundleTransferService } from './bundle-transfer.service';
import { ImportBundleData, ImportBundleDialog } from './import-bundle.dialog';

/** What a surface offers to export — a reference to a saved artifact (full content is resolved from the store). */
export interface TransferItemRef {
    kind: BundleKind;
    id: string;
}

/**
 * The reusable export/import affordance (R6) — one icon-button `mat-menu` dropped into every editor,
 * studio saved-view row and library toolbar, so "one format, three surfaces" is literally one
 * component. `items` are references to the saved artifact(s) this surface offers (a single edited
 * artifact, or a list's filtered rows); the authoritative config is resolved from the store at export
 * time, so a library row's summary is enough. `changed` fires after an import so the host reloads.
 * Export/import go through the shared {@link BundleTransferService} and {@link ImportBundleDialog}.
 */
@Component({
    selector: 'inspecto-transfer-menu',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <button
            type="button"
            mat-icon-button
            [matMenuTriggerFor]="menu"
            [disabled]="busy()"
            [matTooltip]="label()"
            [attr.aria-label]="label()"
        >
            <mat-icon svgIcon="heroicons_outline:arrows-right-left"></mat-icon>
        </button>
        <mat-menu #menu="matMenu">
            <button mat-menu-item [disabled]="!items().length" (click)="exportWithDeps()">
                <mat-icon svgIcon="heroicons_outline:arrow-down-tray"></mat-icon>
                <span>Export with dependencies</span>
            </button>
            <button mat-menu-item [disabled]="!items().length" (click)="exportThisOnly()">
                <mat-icon svgIcon="heroicons_outline:document-arrow-down"></mat-icon>
                <span>Export {{ items().length > 1 ? 'these' : 'this' }} only</span>
            </button>
            @if (lens.canAuthorWorkbench()) {
                <button mat-menu-item (click)="openImport()">
                    <mat-icon svgIcon="heroicons_outline:arrow-up-tray"></mat-icon>
                    <span>Import…</span>
                </button>
            }
        </mat-menu>
    `,
})
export class TransferMenuComponent {
    private transfer = inject(BundleTransferService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    readonly lens = inject(LensService);

    /** References to the saved artifact(s) this surface can export. */
    readonly items = input<TransferItemRef[]>([]);
    /** When set, import is scoped to these kind names (a library imports only its own kind). Typed as
     *  plain strings so a template literal like `['widget']` binds without a strict-template cast. */
    readonly allowedKinds = input<string[] | undefined>(undefined);
    /** Tooltip / aria label for the trigger. */
    readonly label = input('Import / export');
    /** Fires after an import writes at least one artifact — the host should reload. */
    readonly changed = output<void>();

    readonly busy = signal(false);

    exportWithDeps(): void {
        this.exportResolved(true);
    }

    exportThisOnly(): void {
        this.exportResolved(false);
    }

    /** Resolve the surface's references to authoritative store content, then build + download a bundle. */
    private exportResolved(includeDeps: boolean): void {
        this.busy.set(true);
        this.transfer.loadAll().subscribe((available) => {
            const byKey = new Map(available.map((i) => [`${i.kind}/${i.id}`, i]));
            const selected: BundleItem[] = this.items()
                .map((ref) => byKey.get(`${ref.kind}/${ref.id}`))
                .filter((i): i is BundleItem => !!i);
            this.busy.set(false);
            if (!selected.length) {
                this.toastr.warning('Nothing to export yet — save the artifact first.');
                return;
            }
            const { bundle, missing } = this.transfer.buildExport(selected, available, includeDeps);
            this.transfer.download(bundle);
            const extra = bundle.items.length - selected.length;
            this.toastr.success(`Exported ${bundle.items.length} artifact(s)${extra > 0 ? ` (${extra} pulled in as dependencies)` : ''}`);
            if (missing.length) this.toastr.warning(`Unresolved references left out: ${missing.join(', ')}`);
        });
    }

    openImport(): void {
        const data: ImportBundleData = { allowedKinds: this.allowedKinds() };
        this.dialog
            .open(ImportBundleDialog, { data, width: '860px', maxWidth: '95vw', autoFocus: false })
            .afterClosed()
            .subscribe((imported: number | undefined) => {
                if (imported) this.changed.emit();
            });
    }
}
