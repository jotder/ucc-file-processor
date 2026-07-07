import { Component, OnInit, ViewEncapsulation, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { AlertRule, AlertsService, apiErrorMessage, FiredAlert, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { AlertRuleFormData, AlertRuleFormDialog, AlertRuleFormResult } from './alert-rule-form.dialog';

/**
 * Alerts — the core alert engine's surface (v4.1, B5): recent fired alerts (GET /alerts) over the
 * armed Alert Rules (GET /alerts/rules), with a manual evaluation sweep. Rules are authored right
 * here (audit C3 — create/edit/delete, Ops-gated via `canAuthorAlertRules`); the Assistant's
 * diagnose-and-alert skill can still draft one.
 */
@Component({
    selector: 'app-alerts',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        DataTableComponent,
    ],
    templateUrl: './alerts.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class AlertsComponent implements OnInit {
    private api = inject(AlertsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    protected lens = inject(LensService);

    alerts: FiredAlert[] = [];
    rules: AlertRule[] = [];
    loading = false;
    evaluating = false;

    readonly columnDefs: ColDef<FiredAlert>[] = [
        {
            field: 'epochMillis',
            headerName: 'When',
            width: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        {
            field: 'severity',
            headerName: 'Severity',
            width: 120,
            cellRenderer: (p: ICellRendererParams<FiredAlert>) => statusBadgeHtml(p.value as string),
        },
        { field: 'rule', headerName: 'Rule', flex: 1 },
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        { field: 'metric', headerName: 'Metric', width: 140 },
        { field: 'value', headerName: 'Value', width: 110 },
        { field: 'message', headerName: 'Message', flex: 3, wrapText: true, autoHeight: true },
    ];

    readonly ruleColumnDefs: ColDef<AlertRule>[] = [
        { field: 'name', headerName: 'Rule', flex: 1, minWidth: 160 },
        { field: 'metric', headerName: 'Metric', flex: 1, minWidth: 140 },
        {
            headerName: 'Condition',
            width: 170,
            valueGetter: (p) => (p.data ? `${p.data.comparator} ${p.data.threshold} / ${p.data.window}` : ''),
        },
        {
            field: 'severity',
            headerName: 'Severity',
            width: 120,
            cellRenderer: (p: ICellRendererParams<AlertRule>) => statusBadgeHtml(p.value as string),
        },
        {
            headerName: 'Scope',
            width: 160,
            valueGetter: (p) => p.data?.onPipeline || 'every pipeline',
        },
    ];

    /** Edit/delete author monitoring config — Ops-gated (audit C3). */
    get ruleActions(): InspectoRowAction<AlertRule>[] {
        if (!this.lens.canAuthorAlertRules()) return [];
        return [
            { icon: 'heroicons_outline:pencil-square', hint: 'Edit', onClick: (r) => this.editRule(r) },
            { icon: 'heroicons_outline:trash', hint: 'Delete', onClick: (r) => this.removeRule(r) },
        ];
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.recent(100).subscribe({
            next: (a) => {
                this.alerts = a;
                this.loading = false;
            },
            error: () => {
                // Unreachable-backend messaging is the connectivity banner's job (§8) — plain failure toast only.
                this.alerts = [];
                this.loading = false;
                this.toastr.error('Failed to load alerts');
            },
        });
        this.api.rules().subscribe({
            next: (r) => (this.rules = r),
            error: () => (this.rules = []),
        });
    }

    evaluate(): void {
        this.evaluating = true;
        this.api.evaluate().subscribe({
            next: (fired) => {
                this.evaluating = false;
                this.toastr.info(fired.length === 0
                    ? 'Evaluation pass complete — nothing breached'
                    : `${fired.length} alert(s) fired`);
                this.load();
            },
            error: (e) => {
                this.evaluating = false;
                this.toastr.warning(apiErrorMessage(e, 'No alert rules armed — create one under Alert Rules below.'));
            },
        });
    }

    newRule(): void {
        const data: AlertRuleFormData = { existingNames: this.rules.map((r) => r.name) };
        this.dialog
            .open(AlertRuleFormDialog, { data, width: '560px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: AlertRuleFormResult) => {
                if (r?.saved) {
                    this.toastr.success(`Alert rule "${r.saved.name}" armed`);
                    this.load();
                }
            });
    }

    editRule(rule: AlertRule): void {
        const data: AlertRuleFormData = { rule };
        this.dialog
            .open(AlertRuleFormDialog, { data, width: '560px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: AlertRuleFormResult) => {
                if (r?.saved) {
                    this.toastr.success(`Alert rule "${r.saved.name}" saved`);
                    this.load();
                }
            });
    }

    async removeRule(rule: AlertRule): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete alert rule "${rule.name}"?`))) return;
        this.api.removeRule(rule.name).subscribe({
            next: () => {
                this.toastr.success(`Alert rule "${rule.name}" deleted`);
                this.rules = this.rules.filter((r) => r.name !== rule.name);
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Could not delete "${rule.name}".`)),
        });
    }
}
