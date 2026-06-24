import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, Space, SpacesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { SpaceFormDialog } from './space-form.dialog';
import { ImportBundleData, ImportBundleDialog } from './import-bundle.dialog';

/**
 * Spaces admin — manage the projects this server hosts: list every space, create empty ones or onboard
 * them from a bundle, export a whole space or one data source as a zip, import a bundle into a space
 * (with a dry-run preview), and unload/purge a space. Only meaningful on the multi-space (discover)
 * runtime; a single-tenant server shows guidance instead.
 */
@Component({
    selector: 'app-spaces',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './spaces.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class SpacesComponent implements OnInit {
    readonly spaces = inject(SpacesService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    loading = false;
    /** Per-space data-source expansion state + lazily-loaded ids. */
    expanded: Record<string, boolean> = {};
    dataSources: Record<string, string[]> = {};
    dsLoading: Record<string, boolean> = {};

    ngOnInit(): void {
        this.reload();
    }

    reload(): void {
        this.loading = true;
        this.spaces.refresh().subscribe({
            next: () => (this.loading = false),
            error: () => {
                this.loading = false;
                this.toastr.warning('Could not load spaces — is ControlApi running?');
            },
        });
    }

    isCurrent(s: Space): boolean {
        return s.id === this.spaces.currentSpaceId();
    }

    newSpace(): void {
        this.dialog
            .open(SpaceFormDialog, { width: '480px' })
            .afterClosed()
            .subscribe((created?: Space) => {
                if (created) this.reload();
            });
    }

    createFromBundle(): void {
        this.openImport({});
    }

    importInto(s: Space): void {
        this.openImport({ spaceId: s.id });
    }

    private openImport(data: ImportBundleData): void {
        this.dialog
            .open(ImportBundleDialog, { data, width: '640px', maxHeight: '85vh' })
            .afterClosed()
            .subscribe((changed?: boolean) => {
                if (!changed) return;
                this.reload();
                if (data.spaceId && this.expanded[data.spaceId]) this.loadDataSources(data.spaceId, true);
            });
    }

    toggleDataSources(s: Space): void {
        this.expanded[s.id] = !this.expanded[s.id];
        if (this.expanded[s.id] && this.dataSources[s.id] === undefined) this.loadDataSources(s.id);
    }

    private loadDataSources(id: string, force = false): void {
        if (this.dsLoading[id]) return;
        if (!force && this.dataSources[id] !== undefined) return;
        this.dsLoading[id] = true;
        this.spaces.dataSources(id).subscribe({
            next: (ds) => {
                this.dataSources[id] = ds;
                this.dsLoading[id] = false;
            },
            error: (e) => {
                this.dsLoading[id] = false;
                this.toastr.warning(apiErrorMessage(e, `Could not load data sources for "${id}".`));
            },
        });
    }

    exportSpace(s: Space): void {
        this.spaces.exportSpace(s.id).subscribe({
            next: (blob) => this.download(blob, `${s.id}.space.zip`),
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not export "${s.id}".`)),
        });
    }

    exportDataSource(id: string, ds: string): void {
        this.spaces.exportDataSource(id, ds).subscribe({
            next: (blob) => this.download(blob, `${ds}.bundle.zip`),
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not export "${ds}".`)),
        });
    }

    async remove(s: Space, purge: boolean): Promise<void> {
        const msg = purge
            ? `Delete space "${s.id}" AND permanently delete its files on disk? This cannot be undone.`
            : `Unload space "${s.id}"? It is deregistered and stopped; its files on disk are kept.`;
        if (!(await this.confirm.confirmDestructive(msg, { title: purge ? 'Delete & purge' : 'Unload space', requireText: s.id })))
            return;
        this.spaces.remove(s.id, purge).subscribe({
            next: () => {
                this.toastr.success(`Space "${s.id}" ${purge ? 'deleted (purged)' : 'unloaded'}`);
                // If the active space was just removed, drop the selection so the app falls back cleanly.
                if (this.isCurrent(s)) this.spaces.selectSpace(null);
                this.reload();
            },
            error: (e) => {
                const m =
                    e?.status === 409
                        ? 'This server hosts a single space (launch with -Dspaces.root to manage many).'
                        : apiErrorMessage(e, `Could not remove "${s.id}".`);
                this.toastr.error(m);
            },
        });
    }

    /** Trigger a browser download for a fetched blob (object URL, revoked after the click). */
    private download(blob: Blob, filename: string): void {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.click();
        URL.revokeObjectURL(url);
    }
}
