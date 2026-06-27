import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { forkJoin, Observable } from 'rxjs';
import { apiErrorMessage, AuditRow, BatchAuditReport, InboxStatus, PipelinesService } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoRowAction } from 'app/inspecto/grid';
import { BatchDetailDialog } from './batch-detail.dialog';

type TabKey = 'batches' | 'files' | 'lineage' | 'quarantine' | 'commits' | 'report';
type FileFilter = 'ALL' | 'SUCCESS' | 'REJECTED' | 'ERRORED';

/**
 * Pipeline detail — tabbed view over the audit endpoints for a single pipeline (ported from
 * inspector-ui onto the gamma shell): batches / files / lineage (filterable by batchId) /
 * quarantine / commits, plus a Report tab with a date-range producing percentile + throughput
 * stats. Audit rows are loose string maps, so grid columns are derived from the row keys.
 */
@Component({
    selector: 'app-pipeline-detail',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatDatepickerModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTabsModule,
        MatTooltipModule,
        DataTableComponent,
        RouterLink,
    ],
    templateUrl: './pipeline-detail.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class PipelineDetailComponent implements OnInit {
    private api = inject(PipelinesService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);

    name = '';
    loading = false;

    readonly tabs: { id: TabKey; label: string }[] = [
        { id: 'batches', label: 'Batches' },
        { id: 'files', label: 'Files' },
        { id: 'lineage', label: 'Lineage' },
        { id: 'quarantine', label: 'Quarantine' },
        { id: 'commits', label: 'Commits' },
        { id: 'report', label: 'Report' },
    ];
    selectedIndex = 0;
    get activeTab(): TabKey {
        return this.tabs[this.selectedIndex].id;
    }

    rows: AuditRow[] = []; // generic grid (batches/lineage/quarantine/commits)
    lineageBatchId = '';

    // files tab
    allFiles: AuditRow[] = [];
    inbox: InboxStatus | null = null;
    fileStatus: FileFilter = 'ALL';
    readonly fileFilters: FileFilter[] = ['ALL', 'SUCCESS', 'REJECTED', 'ERRORED'];

    // report tab
    from: Date | null = null;
    to: Date | null = null;
    report: BatchAuditReport | null = null;

    ngOnInit(): void {
        this.name = this.route.snapshot.paramMap.get('name') || '';
        this.loadTab();
    }

    onTabChange(): void {
        this.loadTab();
    }

    loadTab(): void {
        const tab = this.activeTab;
        if (tab === 'report') {
            this.loadReport();
            return;
        }
        if (tab === 'files') {
            this.loadFiles();
            return;
        }
        this.loading = true;
        const call: Observable<AuditRow[] | string[]> =
            tab === 'batches' ? this.api.batches(this.name)
            : tab === 'lineage' ? this.api.lineage(this.name, this.lineageBatchId.trim() || undefined)
            : tab === 'quarantine' ? this.api.quarantine(this.name)
            : this.api.commits(this.name);

        call.subscribe({
            next: (data: AuditRow[] | string[]) => {
                this.rows = (data as unknown[]).map((d) =>
                    typeof d === 'string' ? ({ commit: d } as AuditRow) : (d as AuditRow));
                this.loading = false;
            },
            error: () => {
                this.loading = false;
                this.rows = [];
            },
        });
    }

    loadFiles(): void {
        this.loading = true;
        // processed history (audit) + live inbox/processing status, together.
        forkJoin({
            files: this.api.files(this.name),
            inbox: this.api.pending(this.name),
        }).subscribe({
            next: ({ files, inbox }) => {
                this.allFiles = files;
                this.inbox = inbox;
                this.loading = false;
            },
            error: () => {
                this.allFiles = [];
                this.inbox = null;
                this.loading = false;
            },
        });
    }

    loadReport(): void {
        this.loading = true;
        const window = {
            from: this.from ? this.from.toISOString() : undefined,
            to: this.to ? this.to.toISOString() : undefined,
        };
        this.api.report(this.name, window).subscribe({
            next: (r) => {
                this.report = r;
                this.loading = false;
            },
            error: () => {
                this.loading = false;
                this.report = null;
            },
        });
    }

    // ── row actions (audit rows are loose maps; columns are auto-derived by the data table) ──
    private readonly batchActions: InspectoRowAction<AuditRow>[] = [
        {
            icon: 'heroicons_outline:rectangle-group',
            hint: 'Lineage & details',
            onClick: (r) => this.openBatchById(r['batch_id']),
        },
        {
            icon: 'heroicons_outline:arrow-path',
            hint: 'Reprocess this batch',
            onClick: (r) => this.reprocessRow(r),
        },
    ];

    /** Batch actions only on the Batches tab; lineage/quarantine/commits are read-only. */
    get auditRowActions(): InspectoRowAction<AuditRow>[] {
        return this.activeTab === 'batches' ? this.batchActions : [];
    }

    readonly fileRowActions: InspectoRowAction<AuditRow>[] = [
        {
            icon: 'heroicons_outline:rectangle-group',
            hint: 'Open the batch this file belongs to',
            onClick: (r) => this.openBatchById(r['batch_id']),
        },
    ];

    // ── file-processing status ───────────────────────────────────────────────────
    private isSuccess(f: AuditRow): boolean {
        return (f['status'] || '').toUpperCase() === 'SUCCESS';
    }
    private errorRows(f: AuditRow): number {
        return parseInt(f['error_rows'] || '0', 10) || 0;
    }

    get fileStats(): { total: number; success: number; rejected: number; errored: number; rows: number } {
        let success = 0, rejected = 0, errored = 0, rows = 0;
        for (const f of this.allFiles) {
            if (this.isSuccess(f)) success++;
            else rejected++;
            if (this.errorRows(f) > 0) errored++;
            rows += parseInt(f['parsed_rows'] || '0', 10) || 0;
        }
        return { total: this.allFiles.length, success, rejected, errored, rows };
    }

    get filteredFiles(): AuditRow[] {
        return this.allFiles.filter((f) => {
            switch (this.fileStatus) {
                case 'SUCCESS': return this.isSuccess(f);
                case 'REJECTED': return !this.isSuccess(f);
                case 'ERRORED': return this.errorRows(f) > 0;
                default: return true;
            }
        });
    }

    // ── batch detail dialog ─────────────────────────────────────────────────────
    openBatchById(batchId?: string): void {
        const id = (batchId || '').trim();
        if (!id) {
            this.toastr.warning('No batch id on this row');
            return;
        }
        this.dialog.open(BatchDetailDialog, {
            data: { pipeline: this.name, batchId: id },
            width: '880px',
            maxHeight: '85vh',
        });
    }

    reprocessRow(r: AuditRow): void {
        const id = r['batch_id'];
        if (!id) {
            this.toastr.warning('No batch id on this row');
            return;
        }
        this.api.reprocess(this.name, id).subscribe({
            next: () => this.toastr.success(`Reprocess requested for ${id}`),
            error: (e) => this.toastr.error(apiErrorMessage(e, `Reprocess failed for ${id}`)),
        });
    }

    back(): void {
        this.router.navigate(['/pipelines']);
    }
}
