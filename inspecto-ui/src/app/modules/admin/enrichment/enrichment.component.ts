import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef } from 'ag-grid-community';
import { AuditRow, EnrichmentJobView, EnrichmentRunReport, EnrichmentService } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';

type EnrTab = 'runs' | 'lineage' | 'report';

/**
 * Enrichment — Stage-2 jobs with a detail panel (runs / lineage filtered by runId / rollup report)
 * for the selected job (ported from inspector-ui onto the gamma shell). Generic audit rows render
 * as dynamic-column grids; the report tab takes a date range and shows percentile stats.
 */
@Component({
    selector: 'app-enrichment',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatDatepickerModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatTabsModule,
        MatTooltipModule,
        DataTableComponent,
    ],
    templateUrl: './enrichment.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class EnrichmentComponent implements OnInit {
    private api = inject(EnrichmentService);

    jobs: EnrichmentJobView[] = [];
    loading = false;
    unavailable = false;

    selected: EnrichmentJobView | null = null;

    readonly tabs: { id: EnrTab; label: string }[] = [
        { id: 'runs', label: 'Runs' },
        { id: 'lineage', label: 'Lineage' },
        { id: 'report', label: 'Report' },
    ];
    selectedIndex = 0;
    get activeTab(): EnrTab {
        return this.tabs[this.selectedIndex].id;
    }

    rows: AuditRow[] = [];
    detailLoading = false;
    lineageRunId = '';

    from: Date | null = null;
    to: Date | null = null;
    report: EnrichmentRunReport | null = null;

    readonly jobColumns: ColDef<EnrichmentJobView>[] = [
        { field: 'name', headerName: 'Job', flex: 1 },
        { field: 'onPipeline', headerName: 'On pipeline', flex: 1 },
        { field: 'eventTriggered', headerName: 'Event', width: 90 },
        { field: 'scheduleTriggered', headerName: 'Scheduled', width: 110 },
        { field: 'runCount', headerName: 'Runs', width: 90 },
        { field: 'lastRunStatus', headerName: 'Last status', width: 120 },
        { field: 'lastRunTime', headerName: 'Last run', width: 170, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.unavailable = false;
        this.api.list().subscribe({
            next: (j) => {
                this.jobs = j;
                this.loading = false;
            },
            error: (e) => {
                this.loading = false;
                this.jobs = [];
                this.unavailable = e?.status === 404;
            },
        });
    }

    onRowClick(row: EnrichmentJobView): void {
        if (!row) return;
        this.selected = row;
        this.selectedIndex = 0;
        this.report = null;
        this.lineageRunId = '';
        this.loadTab();
    }

    onTabChange(): void {
        this.loadTab();
    }

    loadTab(): void {
        if (!this.selected) return;
        const job = this.selected.name;
        if (this.activeTab === 'report') {
            this.loadReport();
            return;
        }
        this.detailLoading = true;
        const call = this.activeTab === 'runs'
            ? this.api.runs(job)
            : this.api.lineage(job, this.lineageRunId.trim() || undefined);
        call.subscribe({
            next: (r) => {
                this.rows = r;
                this.detailLoading = false;
            },
            error: () => {
                this.rows = [];
                this.detailLoading = false;
            },
        });
    }

    loadReport(): void {
        if (!this.selected) return;
        this.detailLoading = true;
        const window = {
            from: this.from ? this.from.toISOString() : undefined,
            to: this.to ? this.to.toISOString() : undefined,
        };
        this.api.report(this.selected.name, window).subscribe({
            next: (r) => {
                this.report = r;
                this.detailLoading = false;
            },
            error: () => {
                this.report = null;
                this.detailLoading = false;
            },
        });
    }
}
