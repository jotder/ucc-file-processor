import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, Expectation, ExpectationsService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { ExpectationFormDialog, ExpectationFormData, ExpectationFormResult } from './expectation-form.dialog';

/** Human label for the check kind. */
const KIND_LABELS: Record<string, string> = {
    non_null: 'Non-null',
    range: 'Range',
    regex: 'Regex',
    referential: 'Referential',
};

/**
 * Expectations (C2) — data-quality checks (non-null / range / regex / referential) attached to
 * Pipelines/Jobs. "Run check" evaluates one now; "Run all checks" sweeps every enabled one. A FAILED
 * check raises an Incident (correlated `expectation:<name>`) and fans out a notification — follow it
 * on the Incidents pane. Authoring is Builder-lens (canAuthorWorkbench); evaluation is operational
 * and available in every lens.
 */
@Component({
    selector: 'app-expectations',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        DataTableComponent,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './expectations.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ExpectationsComponent implements OnInit {
    private api = inject(ExpectationsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    protected lens = inject(LensService);

    rows: Expectation[] = [];
    loading = false;
    sweeping = false;

    readonly columnDefs: ColDef<Expectation>[] = [
        { field: 'name', headerName: 'Expectation', flex: 1, minWidth: 180 },
        { headerName: 'Check', width: 120, valueGetter: (p) => (p.data ? KIND_LABELS[p.data.kind] ?? p.data.kind : '') },
        {
            headerName: 'Target',
            flex: 1,
            minWidth: 160,
            valueGetter: (p) => (p.data ? `${p.data.targetType}: ${p.data.target}` : ''),
        },
        { field: 'column', headerName: 'Column', width: 130 },
        {
            field: 'severity',
            headerName: 'Severity',
            width: 120,
            cellRenderer: (p: ICellRendererParams<Expectation>) => (p.value ? statusBadgeHtml(p.value as string) : '—'),
        },
        {
            field: 'enabled',
            headerName: 'Enabled',
            width: 110,
            cellRenderer: (p: ICellRendererParams<Expectation>) => statusBadgeHtml(p.value ? 'enabled' : 'disabled'),
        },
        {
            headerName: 'Last result',
            width: 130,
            valueGetter: (p) => p.data?.lastResult?.status ?? '',
            cellRenderer: (p: ICellRendererParams<Expectation>) =>
                p.data?.lastResult
                    ? statusBadgeHtml(p.data.lastResult.status) +
                      (p.data.lastResult.violations ? ` <span class="text-secondary text-xs">${p.data.lastResult.violations}</span>` : '')
                    : '—',
        },
        {
            headerName: 'Checked',
            width: 170,
            valueGetter: (p) => p.data?.lastResult?.checkedAt ?? null,
            valueFormatter: (p) => (p.value ? fmtDateTime(p.value) : '—'),
        },
    ];

    /** Run-check is operational (every lens); edit/delete author config (Builder lens only). */
    get rowActions(): InspectoRowAction<Expectation>[] {
        const ops: InspectoRowAction<Expectation>[] = [
            { icon: 'heroicons_outline:play', hint: 'Run check now', onClick: (e) => this.evaluate(e) },
        ];
        if (!this.lens.canAuthorWorkbench()) return ops;
        return [
            ...ops,
            { icon: 'heroicons_outline:pencil-square', hint: 'Edit', onClick: (e) => this.edit(e) },
            { icon: 'heroicons_outline:clock', hint: 'Version history', onClick: (e) => this.history(e) },
            { icon: 'heroicons_outline:trash', hint: 'Delete', onClick: (e) => this.remove(e) },
        ];
    }

    /** Show version history for an expectation; reload the list after a restore (MET-5). Config edits
     *  archive versions; run-check result stamps do not. */
    history(e: Expectation): void {
        this.dialog
            .open(ComponentHistoryDialog, { data: { type: 'expectation', id: e.name, label: e.name } })
            .afterClosed()
            .subscribe((restored) => {
                if (restored) this.load();
            });
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.list().subscribe({
            next: (rows) => {
                this.rows = rows;
                this.loading = false;
            },
            error: (e) => {
                this.loading = false;
                this.toastr.warning(apiErrorMessage(e, 'Could not load expectations.'));
            },
        });
    }

    newExpectation(): void {
        const data: ExpectationFormData = { existingNames: this.rows.map((r) => r.name) };
        this.dialog
            .open(ExpectationFormDialog, { data, width: '640px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: ExpectationFormResult) => {
                if (r?.saved) {
                    this.toastr.success(`Expectation "${r.saved.name}" created`);
                    this.load();
                }
            });
    }

    edit(e: Expectation): void {
        const data: ExpectationFormData = { expectation: e };
        this.dialog
            .open(ExpectationFormDialog, { data, width: '640px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: ExpectationFormResult) => {
                if (r?.saved) {
                    this.toastr.success(`Expectation "${r.saved.name}" saved`);
                    this.load();
                }
            });
    }

    /** Run one check; a failure raises an Incident — say so, and point at where it landed. */
    evaluate(e: Expectation): void {
        this.api.evaluate(e.name).subscribe({
            next: (res) => {
                this.patch(res);
                if (res.lastResult?.status === 'FAILED') {
                    this.toastr.warning(
                        `"${res.name}" FAILED (${res.lastResult.violations} violation(s)) — an Incident was raised.`,
                    );
                } else {
                    this.toastr.success(`"${res.name}" passed.`);
                }
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Could not evaluate "${e.name}".`)),
        });
    }

    /** Sweep every enabled check (like the Alerts manual sweep). */
    evaluateAll(): void {
        this.sweeping = true;
        this.api.evaluateAll().subscribe({
            next: (rows) => {
                this.rows = rows;
                this.sweeping = false;
                const failed = rows.filter((r) => r.lastResult?.status === 'FAILED').length;
                if (failed) this.toastr.warning(`${failed} expectation(s) FAILED — Incidents were raised.`);
                else this.toastr.success('All enabled expectations passed.');
            },
            error: (err) => {
                this.sweeping = false;
                this.toastr.error(apiErrorMessage(err, 'Could not run the sweep.'));
            },
        });
    }

    async remove(e: Expectation): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete expectation "${e.name}"?`))) return;
        this.api.remove(e.name).subscribe({
            next: () => {
                this.toastr.success(`Expectation "${e.name}" deleted`);
                this.rows = this.rows.filter((r) => r.name !== e.name);
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Could not delete "${e.name}".`)),
        });
    }

    /** Replace one row (reassign the array so the grid re-renders). */
    private patch(next: Expectation): void {
        this.rows = this.rows.map((r) => (r.name === next.name ? next : r));
    }
}
