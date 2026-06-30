import { ChangeDetectionStrategy, Component, Input, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef } from 'ag-grid-community';
import { LineageService, StoreLineage } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';

/**
 * Store lineage panel (catalog node inspector, table-kind nodes): the files that fed this store (ingest
 * upstream, from `GET /lineage?store=`) and the authored flows that consume it (downstream). The store is the
 * bridge between ingest file-lineage and flow step-provenance — see `docs/GLOSSARY.md` §11.
 * Thin container: fetches via {@link LineageService}; rendering reuses the shared `<inspecto-data-table>`.
 */
@Component({
    selector: 'app-store-lineage',
    standalone: true,
    imports: [MatProgressSpinnerModule, DataTableComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (loading()) {
            <div class="flex items-center gap-3 py-3">
                <mat-progress-spinner diameter="20" mode="indeterminate" />
                <span class="text-secondary text-sm">Loading lineage…</span>
            </div>
        } @else if (failed()) {
            <p class="text-secondary text-sm">Lineage is unavailable for this store.</p>
        } @else if (data(); as d) {
            <div class="mb-1 text-sm font-semibold">Files into this store ({{ d.upstream.length }})</div>
            <inspecto-data-table
                tier="mini"
                sourceName="store-upstream"
                [rows]="d.upstream"
                [columns]="upstreamColumns"
                height="10rem"
                noRowsTitle="No ingest lineage recorded"
            />

            <div class="mb-1 mt-4 text-sm font-semibold">Consumed by ({{ d.downstream.length }})</div>
            @if (d.downstream.length) {
                <ul class="text-sm">
                    @for (f of d.downstream; track f.flow) {
                        <li class="py-0.5">
                            <span class="font-mono">{{ f.flow }}</span>
                            <span class="text-secondary"> → {{ f.sinks.length ? f.sinks.join(', ') : '—' }}</span>
                        </li>
                    }
                </ul>
            } @else {
                <p class="text-secondary text-sm">No flows consume this store.</p>
            }
        }
    `,
})
export class StoreLineageComponent {
    private api = inject(LineageService);
    private destroyRef = inject(DestroyRef);

    readonly loading = signal(false);
    readonly failed = signal(false);
    readonly data = signal<StoreLineage | null>(null);

    /** The store (table) name to trace. Setting it (re)loads the lineage. */
    @Input({ required: true }) set store(name: string) {
        if (name && name.trim()) this.fetch(name.trim());
    }

    readonly upstreamColumns: ColDef[] = [
        { field: 'inputFile', headerName: 'Input file', flex: 1 },
        { field: 'partition', headerName: 'Partition', flex: 1 },
        { field: 'rowCount', headerName: 'Rows', width: 100, type: 'numericColumn' },
        { field: 'pipeline', headerName: 'Pipeline', width: 140 },
    ];

    private fetch(store: string): void {
        this.loading.set(true);
        this.failed.set(false);
        this.data.set(null);
        this.api
            .lineage(store)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (d) => {
                    this.data.set(d);
                    this.loading.set(false);
                },
                error: () => {
                    this.failed.set(true);
                    this.loading.set(false);
                },
            });
    }
}
