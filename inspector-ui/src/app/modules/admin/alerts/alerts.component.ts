import { Component, OnInit, ViewEncapsulation, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { AlertRule, AlertsService, FiredAlert } from 'app/ucc/api';
import { UCC_DEFAULT_COL_DEF, UccGridThemeService } from 'app/ucc/grid';

/**
 * Alerts — the core alert engine's surface (v4.1, B5): recent fired alerts (GET /alerts) over the
 * armed *_alert.toon rules (GET /alerts/rules), with a manual evaluation sweep. Rules are drafted
 * by the diagnose-and-alert assist skill and armed by saving the reviewed .toon next to the
 * pipeline configs.
 */
@Component({
    selector: 'app-alerts',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        AgGridAngular,
    ],
    templateUrl: './alerts.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class AlertsComponent implements OnInit {
    private api = inject(AlertsService);
    private toastr = inject(ToastrService);
    readonly themeSvc = inject(UccGridThemeService);

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
            valueFormatter: (p) => (p.value ? new Date(p.value).toLocaleString() : ''),
        },
        { field: 'severity', headerName: 'Severity', width: 120 },
        { field: 'rule', headerName: 'Rule', flex: 1 },
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        { field: 'metric', headerName: 'Metric', width: 140 },
        { field: 'value', headerName: 'Value', width: 110 },
        { field: 'message', headerName: 'Message', flex: 3, wrapText: true, autoHeight: true },
    ];

    readonly defaultColDef = UCC_DEFAULT_COL_DEF;

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
                this.alerts = [];
                this.loading = false;
                this.toastr.warning('Could not load alerts — is ControlApi running?');
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
                this.toastr.warning(e?.error?.error ?? 'No alert rules armed (save a *_alert.toon next to the configs)');
            },
        });
    }
}
