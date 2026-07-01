import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ReportsService, RunStatus, StatusReport } from 'app/inspecto/api';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';

/** A summary card above the grid. */
interface MetricCard {
    label: string;
    value: string;
}

/**
 * Processing Status — the cross-pipeline rollup Operations lacked: every pipeline's committed/
 * quarantine counts and last-batch outcome in one grid (GET /status), instead of drilling into
 * each pipeline's own Runs > Files/Lineage tabs one at a time. A row opens that pipeline's Run
 * detail for the full provenance/lineage/quarantine breakdown — this page doesn't duplicate it.
 */
@Component({
    selector: 'app-processing-status',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule, DataTableComponent],
    templateUrl: './processing-status.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class ProcessingStatusComponent implements OnInit {
    private api = inject(ReportsService);
    private router = inject(Router);

    loading = false;
    report: StatusReport | null = null;
    cards: MetricCard[] = [];

    readonly columnDefs: ColDef<RunStatus>[] = [
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        {
            field: 'paused',
            headerName: 'State',
            width: 110,
            cellRenderer: (p: ICellRendererParams<RunStatus>) => statusBadgeHtml(p.value ? 'PAUSED' : 'RUNNING'),
        },
        { field: 'committedBatches', headerName: 'Committed batches', width: 170 },
        { field: 'quarantineFiles', headerName: 'Quarantine files', width: 160 },
        {
            field: 'lastBatchStatus',
            headerName: 'Last batch',
            width: 130,
            cellRenderer: (p: ICellRendererParams<RunStatus>) => (p.value ? statusBadgeHtml(p.value) : '—'),
        },
        { field: 'lastBatchId', headerName: 'Last batch id', flex: 1, valueFormatter: (p) => p.value ?? '—' },
        { field: 'lastBatchTime', headerName: 'Last batch time', width: 180, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    readonly rowActions: InspectoRowAction<RunStatus>[] = [
        {
            icon: 'heroicons_outline:rectangle-group',
            hint: 'Open provenance & lineage for this pipeline',
            onClick: (r) => this.router.navigate(['/runs', r.pipeline]),
        },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.status().subscribe({
            next: (r) => {
                this.report = r;
                this.cards = [
                    { label: 'Pipelines', value: String(r.pipelineCount) },
                    { label: 'Paused', value: String(r.pausedCount) },
                    { label: 'Committed batches', value: r.totalCommittedBatches.toLocaleString() },
                    { label: 'Quarantine files', value: String(r.totalQuarantineFiles) },
                ];
                this.loading = false;
            },
            error: () => {
                this.report = null;
                this.cards = [];
                this.loading = false;
            },
        });
    }
}
