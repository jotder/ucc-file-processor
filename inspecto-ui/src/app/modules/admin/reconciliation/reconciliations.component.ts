import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { buildReconciliation, Reconciliation, ReconciliationsService } from 'app/inspecto/reconciliation';
import { ReconciliationFormDialog, ReconciliationFormResult } from './reconciliation-form.dialog';

/**
 * Reconciliation (C9) — the list of Dataset-vs-Dataset reconciliations; open one to run it and drill its
 * breaks. Authoring (create) is a Business surface per the plan; open to every lens (no identity model).
 */
@Component({
    selector: 'app-reconciliations',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, DataTableComponent, InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './reconciliations.component.html',
})
export class ReconciliationsComponent implements OnInit {
    private api = inject(ReconciliationsService);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    private toastr = inject(ToastrService);

    readonly reconciliations = signal<Reconciliation[]>([]);
    readonly loading = signal(false);

    readonly columns: ColDef<Reconciliation>[] = [
        { field: 'name', headerName: 'Reconciliation', flex: 1 },
        { field: 'leftDataset', headerName: 'Left', flex: 1 },
        { field: 'rightDataset', headerName: 'Right', flex: 1 },
        { headerName: 'Keys', width: 140, valueGetter: (p) => (p.data?.keyColumns ?? []).join(', ') },
        { field: 'lastRunAt', headerName: 'Last run', width: 180, valueFormatter: (p) => (p.value ? fmtDateTime(p.value) : 'never') },
    ];

    readonly rowActions: InspectoRowAction<Reconciliation>[] = [
        { icon: 'heroicons_outline:arrow-right', hint: 'Open', onClick: (r) => this.open(r) },
        { icon: 'heroicons_outline:document-duplicate', hint: 'Duplicate', onClick: (r) => this.duplicate(r) },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.list().subscribe({
            next: (r) => {
                this.reconciliations.set(r);
                this.loading.set(false);
            },
            error: () => {
                this.reconciliations.set([]);
                this.loading.set(false);
            },
        });
    }

    open(r: Reconciliation): void {
        this.router.navigate(['/reconciliation', r.id]);
    }

    create(): void {
        this.openForm({});
    }

    /** The template flow (design §8): prefill from an existing recon, create a fresh one (no run state). */
    duplicate(source: Reconciliation): void {
        this.openForm({ recon: source, duplicate: true });
    }

    private openForm(data: object): void {
        this.dialog
            .open(ReconciliationFormDialog, { width: '640px', maxHeight: '85vh', data })
            .afterClosed()
            .subscribe((result?: ReconciliationFormResult) => {
                if (!result) return;
                const r: Reconciliation = {
                    ...buildReconciliation(result.name, result.leftDataset, result.rightDataset, result.keyColumns, result.compareColumns),
                    thirdDataset: result.thirdDataset,
                    bands: result.bands,
                };
                this.api.create(r).subscribe({
                    next: () => this.router.navigate(['/reconciliation', r.id]),
                    error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not create the reconciliation')),
                });
            });
    }
}
