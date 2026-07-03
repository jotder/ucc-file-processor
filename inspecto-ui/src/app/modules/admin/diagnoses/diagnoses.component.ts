import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { AssistService, Diagnosis } from 'app/inspecto/api';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';
import { DiagnosisDetailDialog } from './diagnosis-detail.dialog';

/**
 * Diagnoses — recent failure root-cause analyses (GET /assist/diagnoses), on the gamma shell:
 * ag-Grid Community (quick filter + pagination), MatDialog detail, ngx-toastr notifications.
 */
@Component({
    selector: 'app-diagnoses',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        DataTableComponent,
    ],
    templateUrl: './diagnoses.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class DiagnosesComponent implements OnInit {
    private api = inject(AssistService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);

    diagnoses: Diagnosis[] = [];
    loading = false;
    limit = 50;

    readonly columnDefs: ColDef<Diagnosis>[] = [
        {
            field: 'epochMillis',
            headerName: 'When',
            width: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        { field: 'batchId', headerName: 'Batch', flex: 1 },
        {
            field: 'severity',
            headerName: 'Severity',
            width: 120,
            cellRenderer: (p: ICellRendererParams<Diagnosis>) => statusBadgeHtml(p.value as string),
        },
        { field: 'rootCause', headerName: 'Root cause', flex: 3, wrapText: true, autoHeight: true },
        { field: 'heuristicOnly', headerName: 'Heuristic', width: 110 },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.api.diagnoses(this.limit).subscribe({
            next: (d) => {
                this.diagnoses = d;
                this.loading = false;
            },
            error: () => {
                this.diagnoses = [];
                this.loading = false;
                // Unreachable-backend messaging is the connectivity banner's job (§8) — plain failure toast only.
                this.toastr.error('Failed to load diagnoses');
            },
        });
    }

    openDetail(row: Diagnosis): void {
        if (!row) return;
        this.dialog.open(DiagnosisDetailDialog, { data: row, width: '760px', maxHeight: '85vh' });
    }
}
