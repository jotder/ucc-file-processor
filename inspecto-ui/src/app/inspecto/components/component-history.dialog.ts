import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ComponentsService, ComponentType, ComponentVersion } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from './empty-state.component';

export interface ComponentHistoryData {
    type: ComponentType;
    id: string;
    /** Optional display label (defaults to the id). */
    label?: string;
}

/**
 * Reusable component version-history dialog (MET-5). Lists a component's prior saved copies (newest
 * first) via {@link ComponentsService.versions} and restores one via {@link ComponentsService.restore}.
 * Restoring makes an archived copy current; the outgoing copy is archived first, so it is reversible.
 * Closes with {@code true} once a restore succeeds so the host can reload its view.
 */
@Component({
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, MatProgressSpinnerModule, InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Version history</h2>
        <mat-dialog-content class="w-[34rem] max-w-full">
            <div class="text-secondary mb-3 text-sm">
                Prior saved copies of {{ data.type }} <strong>{{ data.label || data.id }}</strong>. Restoring
                makes a copy current — the current version is archived first, so it stays reversible.
            </div>
            @if (loading()) {
                <div class="flex items-center gap-3 py-2 text-sm"><mat-spinner diameter="20"></mat-spinner><span>Loading history…</span></div>
            } @else if (!versions().length) {
                <inspecto-empty-state
                    icon="heroicons_outline:clock"
                    title="No earlier versions"
                    message="Edits are archived here. Save a change to this component and its prior copy will appear."
                />
            } @else {
                <table class="w-full text-sm">
                    <thead class="text-secondary">
                        <tr class="text-left">
                            <th scope="col" class="py-1 pr-3 font-medium">Version</th>
                            <th scope="col" class="py-1 pr-3 font-medium">Saved</th>
                            <th scope="col" class="py-1 pr-3 font-medium">Hash</th>
                            <th scope="col" class="py-1"><span class="sr-only">Actions</span></th>
                        </tr>
                    </thead>
                    <tbody>
                        @for (v of versions(); track v.version) {
                            <tr class="border-t border-gray-200 dark:border-gray-700">
                                <td class="py-1.5 pr-3">v{{ v.version }}</td>
                                <td class="py-1.5 pr-3">{{ savedAt(v) }}</td>
                                <td class="py-1.5 pr-3 font-mono text-xs">{{ v.contentHash.slice(0, 12) }}</td>
                                <td class="py-1.5 text-right">
                                    <button mat-stroked-button [disabled]="restoring()" (click)="restore(v)">Restore</button>
                                </td>
                            </tr>
                        }
                    </tbody>
                </table>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class ComponentHistoryDialog {
    readonly data = inject<ComponentHistoryData>(MAT_DIALOG_DATA);
    private components = inject(ComponentsService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ComponentHistoryDialog>);

    readonly loading = signal(true);
    readonly restoring = signal(false);
    readonly versions = signal<ComponentVersion[]>([]);

    constructor() {
        this.components.versions(this.data.type, this.data.id).subscribe({
            next: (v) => {
                this.versions.set(v);
                this.loading.set(false);
            },
            error: (e) => {
                this.versions.set([]);
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not load version history.'));
            },
        });
    }

    savedAt(v: ComponentVersion): string {
        if (!v.savedAt) return '—';
        const d = new Date(v.savedAt);
        return isNaN(d.getTime()) ? v.savedAt : d.toLocaleString();
    }

    async restore(v: ComponentVersion): Promise<void> {
        const ok = await this.confirm.confirm(
            `Restore ${this.data.type} "${this.data.label || this.data.id}" to version ${v.version}?`,
            'Restore version',
        );
        if (!ok) return;
        this.restoring.set(true);
        this.components.restore(this.data.type, this.data.id, v.version).subscribe({
            next: () => {
                this.toastr.success(`Restored to version ${v.version}.`);
                this.ref.close(true);
            },
            error: (e) => {
                this.restoring.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not restore that version.'));
            },
        });
    }
}
