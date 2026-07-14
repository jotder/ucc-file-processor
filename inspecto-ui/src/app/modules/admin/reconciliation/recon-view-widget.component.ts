import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ColDef } from 'ag-grid-community';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { TreeNode, TreeTableComponent } from 'app/inspecto/tree-table';
import {
    boardColumns, buildBoardTree, DEFAULT_BANDS, Reconciliation, ReconciliationsService, ReconRunResult,
} from 'app/inspecto/reconciliation';
import { ReconExecService } from './recon-exec.service';

/**
 * Read-only **Reconciliation widget** host (DAT-7 P3): renders a saved `reconciliation` Component on a
 * dashboard tile by re-running its aggregate comparison through the shared exec seam and showing the
 * compact Board (dimension tree + banded Δ% per measure — values stay on the full pane). Loaded lazily
 * through the viz component-loader registry (`widget.kind`); editing happens at `/reconciliation/:id`.
 */
@Component({
    selector: 'app-recon-view-widget',
    standalone: true,
    imports: [RouterLink, TreeTableComponent, InspectoAlertComponent, InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex h-full min-h-0 flex-col">
            @if (error(); as message) {
                <inspecto-alert class="block" variant="warning">{{ message }}</inspecto-alert>
            } @else if (result(); as res) {
                <div class="text-secondary mb-1 flex flex-wrap items-center gap-x-3 text-xs">
                    <a [routerLink]="['/reconciliation', recon()?.id]" class="hover:underline">
                        {{ recon()?.leftDataset }} vs {{ recon()?.rightDataset }}
                    </a>
                    <span>{{ res.summary.matchedKeys }} matched</span>
                    <span>{{ res.summary.byType.missing_right + res.summary.byType.missing_left }} missing</span>
                    <span>{{ res.summary.byType.value_break }} value breaks</span>
                </div>
                <inspecto-tree-table
                    class="min-h-0 flex-auto"
                    [treeHeader]="recon()?.keyColumns?.join(' › ') ?? 'Dimension'"
                    exportName="reconciliation-widget"
                    [nodes]="nodes()"
                    [columns]="columns()"
                    [groupDefaultExpanded]="1"
                    height="100%"
                    noRowsTitle="No key groups"
                />
            } @else if (loaded()) {
                <inspecto-empty-state icon="heroicons_outline:scale" message="No saved Reconciliation bound to this widget." />
            } @else {
                <div class="text-secondary flex h-full items-center justify-center text-sm">Loading…</div>
            }
        </div>
    `,
})
export class ReconViewWidgetComponent {
    private reconApi = inject(ReconciliationsService);
    private exec = inject(ReconExecService);

    /** The saved `reconciliation` id this widget renders (the widget's binding). */
    readonly viewId = input<string | undefined>(undefined);

    readonly recon = signal<Reconciliation | null>(null);
    readonly result = signal<ReconRunResult | null>(null);
    readonly error = signal<string | null>(null);
    /** The recon fetch settled (found or not) — gates the not-found empty state vs the loading strip. */
    readonly loaded = signal(false);

    readonly nodes = computed<TreeNode[]>(() => {
        const res = this.result();
        return res ? buildBoardTree(res, this.recon()?.bands ?? DEFAULT_BANDS) : [];
    });

    /** Compact tile columns: the banded Δ% per measure per compared side (values live on the Board pane). */
    readonly columns = computed<ColDef[]>(() => {
        const res = this.result();
        return res ? boardColumns(res, this.recon()?.bands ?? DEFAULT_BANDS) : [];
    });

    constructor() {
        effect(() => {
            const id = this.viewId();
            this.recon.set(null);
            this.result.set(null);
            this.error.set(null);
            this.loaded.set(false);
            if (!id) {
                this.loaded.set(true);
                return;
            }
            this.reconApi.get(id).subscribe({
                next: (recon) => {
                    this.recon.set(recon);
                    this.loaded.set(true);
                    this.exec.run(recon)
                        .then((r) => this.result.set(r))
                        .catch((e: unknown) => this.error.set(e instanceof Error ? e.message : 'The reconciliation run failed.'));
                },
                error: () => {
                    this.loaded.set(true);
                    this.error.set(`Could not load the saved reconciliation “${id}”.`);
                },
            });
        });
    }
}

