import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, LensService } from 'app/inspecto/api';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { buildRequirement, Requirement, RequirementsService } from 'app/inspecto/requirement';
import { RequirementFormDialog, RequirementFormResult } from './requirement-form.dialog';
import { RequirementDecisionDialog, RequirementDecisionResult } from './requirement-decision.dialog';

/**
 * Requirements intake (C1) — Business authors KPI/Report/Reconciliation/Rule requirements; Builder (and
 * Ops) triage the same list as a queue (accept/reject, then deliver). One shared view, action visibility
 * gated by lens — submitting is open to every lens (no auth/identity to restrict "who is Business"), only
 * the decide/deliver actions are gated per the Wave-3 interview decision (2026-07-03).
 */
@Component({
    selector: 'app-requirements',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, DataTableComponent, InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './requirements.component.html',
})
export class RequirementsComponent implements OnInit {
    private api = inject(RequirementsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    /** Business lens = read-only — hides the decide/deliver actions in the detail dialog. */
    protected lens = inject(LensService);

    readonly requirements = signal<Requirement[]>([]);
    readonly loading = signal(false);

    readonly columns: ColDef<Requirement>[] = [
        { field: 'title', headerName: 'Title', flex: 1 },
        { field: 'kind', headerName: 'Kind', width: 150 },
        {
            field: 'status',
            headerName: 'Status',
            width: 130,
            cellRenderer: (p: ICellRendererParams<Requirement>) => statusBadgeHtml(p.value as string),
        },
        { field: 'submittedAt', headerName: 'Submitted', width: 180, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    readonly rowActions: InspectoRowAction<Requirement>[] = [
        { icon: 'heroicons_outline:eye', hint: 'View', onClick: (r) => this.openDetail(r) },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.list().subscribe({
            next: (r) => {
                this.requirements.set(r);
                this.loading.set(false);
            },
            error: () => {
                this.requirements.set([]);
                this.loading.set(false);
            },
        });
    }

    submit(): void {
        this.dialog
            .open(RequirementFormDialog, { width: '480px' })
            .afterClosed()
            .subscribe((result?: RequirementFormResult) => {
                if (!result) return;
                const r = buildRequirement(result.title, result.kind, result.description);
                this.api.create(r).subscribe({
                    next: () => {
                        this.toastr.success(`Requirement "${r.title}" submitted`);
                        this.load();
                    },
                    error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not submit the requirement')),
                });
            });
    }

    openDetail(r: Requirement): void {
        this.dialog
            .open(RequirementDecisionDialog, { data: r, width: '520px' })
            .afterClosed()
            .subscribe((result?: RequirementDecisionResult) => {
                if (!result || !this.lens.canTriageRequirements()) return;
                const updated$ =
                    result.action === 'decide'
                        ? this.api.decide(r.id, result.accept, result.note)
                        : this.api.deliver(r.id, result.note);
                updated$.subscribe({
                    next: (updated) => {
                        this.toastr.success(`"${r.title}" ${updated.status}`);
                        this.load();
                    },
                    error: (e) => this.toastr.error(apiErrorMessage(e, `Could not update "${r.title}"`)),
                });
            });
    }
}
