import { Component, OnInit, ViewEncapsulation, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { AgentApproval, apiErrorMessage, ApprovalDecision, ApprovalsService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';

/**
 * Approvals Inbox (AGT-5 P3, autonomy L2) — the operator surface over the agent's approval gate. A
 * mutating agent tool call parks in the intelligence module's inbox (`GET /agent/approvals`); the
 * operator reviews the request's dry-run `preview` and approves or declines it
 * (`POST /agent/approvals/{id}/decision`), which resumes or denies the parked tool. Deciding is
 * Ops-gated (`canOperateRuns`); every lens can read the inbox. The page degrades to an empty grid on
 * failure (module absent / act tier off) with a plain toast, mirroring the Alerts pane.
 */
@Component({
    selector: 'app-approvals',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, DataTableComponent],
    templateUrl: './approvals.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ApprovalsComponent implements OnInit {
    private api = inject(ApprovalsService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    protected lens = inject(LensService);

    approvals: AgentApproval[] = [];
    loading = false;

    readonly columnDefs: ColDef<AgentApproval>[] = [
        {
            field: 'requestedAt',
            headerName: 'Requested',
            width: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        { field: 'tool', headerName: 'Tool', width: 170 },
        { field: 'agentActor', headerName: 'Actor', width: 160 },
        {
            field: 'status',
            headerName: 'Status',
            width: 120,
            cellRenderer: (p: ICellRendererParams<AgentApproval>) => statusBadgeHtml(p.value as string),
        },
        { field: 'summary', headerName: 'Request', flex: 1, wrapText: true, autoHeight: true },
    ];

    /** Approve/decline a pending request — Ops-gated (`canOperateRuns`); nothing for a read-only lens. */
    get rowActions(): InspectoRowAction<AgentApproval>[] {
        if (!this.lens.canOperateRuns()) return [];
        return [
            {
                icon: 'heroicons_outline:check',
                hint: 'Approve',
                visible: (a) => a.status === 'PENDING',
                onClick: (a) => this.approve(a),
            },
            {
                icon: 'heroicons_outline:x-mark',
                hint: 'Decline',
                visible: (a) => a.status === 'PENDING',
                onClick: (a) => this.decline(a),
            },
        ];
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.list(100).subscribe({
            next: (a) => {
                this.approvals = a;
                this.loading = false;
            },
            error: () => {
                // Module-absent (503) / act-tier-off (empty) both land here as an empty inbox + toast;
                // connectivity messaging is the banner's job, as on the Alerts pane.
                this.approvals = [];
                this.loading = false;
                this.toastr.error('Failed to load approvals');
            },
        });
    }

    async approve(a: AgentApproval): Promise<void> {
        if (!(await this.confirm.confirm(this.review(a), `Approve ${a.tool}?`))) return;
        this.decide(a, 'approve');
    }

    async decline(a: AgentApproval): Promise<void> {
        const ok = await this.confirm.confirmDestructive(this.review(a), {
            title: `Decline ${a.tool}?`,
            confirmText: 'Decline',
        });
        if (!ok) return;
        this.decide(a, 'decline');
    }

    /** The dry-run the operator reviews at decision time (rendered `whitespace-pre-wrap` in the dialog). */
    private review(a: AgentApproval): string {
        const args = JSON.stringify(a.arguments ?? {}, null, 2);
        const preview = JSON.stringify(a.preview ?? {}, null, 2);
        return `${a.summary}\n\nActor: ${a.agentActor}\n\nArguments:\n${args}\n\nPreview (dry-run):\n${preview}`;
    }

    private decide(a: AgentApproval, decision: ApprovalDecision): void {
        this.api.decide(a.id, decision).subscribe({
            next: (updated) => {
                this.toastr.success(decision === 'approve' ? 'Request approved' : 'Request declined');
                // Reflect the terminal status in place — the PENDING-only row actions then fall away.
                this.approvals = this.approvals.map((x) => (x.id === updated.id ? updated : x));
            },
            error: (err) =>
                this.toastr.error(apiErrorMessage(err, `Could not ${decision} the request (it may have lapsed).`)),
        });
    }
}
