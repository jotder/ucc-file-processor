import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { TransferMenuComponent } from 'app/inspecto/transfer';
import { Dataset } from './dataset-types';
import { DatasetsService } from './datasets.service';

/**
 * Studio **Datasets** — the data-source abstractions (physical / virtual / materialized) widgets build on.
 * Lists the `dataset` components (mock-served) with their kind + source, and links to the editor. Mirrors
 * `ConnectionsComponent`; the first Studio surface on the unified component model.
 */
@Component({
    selector: 'app-datasets',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        RouterLink,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
        TransferMenuComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './datasets.component.html',
})
export class DatasetsComponent implements OnInit {
    private api = inject(DatasetsService);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    readonly datasets = signal<Dataset[]>([]);
    readonly loading = signal(false);
    readonly writesDisabled = signal(false);
    readonly filterText = signal('');

    readonly visibleDatasets = computed(() => {
        const q = this.filterText().trim().toLowerCase();
        const all = this.datasets();
        if (!q) return all;
        return all.filter((d) => [d.id, d.kind, d.sourceName].join(' ').toLowerCase().includes(q));
    });

    /** The filtered datasets as transfer references — what the export/import menu offers. */
    readonly transferItems = computed(() => this.visibleDatasets().map((d) => ({ kind: 'dataset' as const, id: d.id })));

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.list().subscribe({
            next: (d) => {
                this.datasets.set(d);
                this.loading.set(false);
            },
            error: () => {
                this.datasets.set([]);
                this.loading.set(false);
                this.toastr.warning('Could not load datasets — is ControlApi running?');
            },
        });
    }

    onFilter(ev: Event): void {
        this.filterText.set((ev.target as HTMLInputElement).value);
    }

    /** Column count gives a quick sense of the dataset's shape on the card. */
    columnCount(d: Dataset): number {
        return d.columns.length;
    }

    async remove(d: Dataset): Promise<void> {
        if (
            !(await this.confirm.confirmDestructive(`Delete dataset "${d.id}"?`, { title: 'Delete dataset' }))
        )
            return;
        this.api.remove(d.id).subscribe({
            next: () => {
                this.toastr.success(`Dataset "${d.id}" deleted`);
                this.load();
            },
            error: (e) => {
                if (e?.status === 503) this.writesDisabled.set(true);
                this.toastr.error(
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : apiErrorMessage(e, `Could not delete "${d.id}".`),
                );
            },
        });
    }
}
