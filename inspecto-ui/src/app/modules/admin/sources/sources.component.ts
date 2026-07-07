import { Component, inject, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef } from 'ag-grid-community';
import { ChartData } from 'chart.js';
import {
    AcquisitionMetrics,
    AcquisitionMetricsService,
    apiErrorMessage,
    PipelinesService,
    SourceView,
    SourcesService,
} from 'app/inspecto/api';
import { InspectoAuthService } from 'app/inspecto/auth.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ToastrService } from 'ngx-toastr';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { actionsColumn, refreshActionsCells, INSPECTO_DEFAULT_COL_DEF, InspectoGridThemeService } from 'app/inspecto/grid';
import { CHART_SERIES } from 'app/inspecto/theme/chart-tokens';
import { SourceDetailDialog } from './source-detail.dialog';

/** A summary card above the grid. */
interface MetricCard {
    label: string;
    value: string;
}

/**
 * Acquisition / Sources — every configured source across all pipelines (GET /sources) with an
 * acquisition-metrics strip (GET /metrics/acquisition). Run-now triggers the owning pipeline; the
 * details action opens the full source config + live inbox status.
 */
@Component({
    selector: 'app-sources',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        AgGridAngular,
        InspectoChartComponent,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './sources.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class SourcesComponent implements OnInit {
    private api = inject(SourcesService);
    private metricsApi = inject(AcquisitionMetricsService);
    private pipelines = inject(PipelinesService);
    private auth = inject(InspectoAuthService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    readonly themeSvc = inject(InspectoGridThemeService);

    sources: SourceView[] = [];
    loading = false;
    unavailable = false;
    quickFilter = '';

    cards: MetricCard[] = [];
    discoveredData: ChartData | null = null;

    get canControl(): boolean {
        return this.auth.hasControl();
    }

    readonly defaultColDef = INSPECTO_DEFAULT_COL_DEF;
    readonly columnDefs: ColDef<SourceView>[] = [
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        { field: 'id', headerName: 'Source', flex: 1 },
        { field: 'connector', headerName: 'Connector', width: 120 },
        { field: 'connection', headerName: 'Connection', flex: 1, valueFormatter: (p) => p.value ?? '—' },
        { field: 'duplicateMode', headerName: 'Dedup', width: 120 },
        {
            colId: 'watermark',
            headerName: 'Watermark',
            flex: 1,
            valueGetter: (p) => {
                const wm = p.data?.incrementalWatermark ?? '—';
                const db = p.data?.dbWatermarkCurrent;
                return db ? `${wm} (@ ${db})` : wm;
            },
        },
        { field: 'fetchParallel', headerName: 'Parallel', width: 110 },
        { field: 'guarantee', headerName: 'Guarantee', width: 140 },
        actionsColumn<SourceView>([
            {
                icon: 'heroicons_outline:play',
                hint: 'Run now',
                visible: () => this.canControl,
                onClick: (s) => this.trigger(s),
            },
            {
                icon: 'heroicons_outline:information-circle',
                hint: 'Details',
                onClick: (s) => this.openDetail(s),
            },
        ], 120),
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading = true;
        this.unavailable = false;
        this.api.list().subscribe({
            next: (s) => {
                this.sources = s;
                this.loading = false;
            },
            error: (e) => {
                this.loading = false;
                this.sources = [];
                this.unavailable = e?.status === 404;
            },
        });
        this.metricsApi.get().subscribe({
            next: (m) => this.buildMetrics(m),
            error: () => {
                this.cards = [];
                this.discoveredData = null;
            },
        });
    }

    /** Sum every label-series sample of a counter/gauge metric. */
    private total(m: AcquisitionMetrics, name: string): number {
        const series = m[name]?.series ?? [];
        return series.reduce((acc, s) => acc + (s.value ?? s.sum ?? 0), 0);
    }

    private buildMetrics(m: AcquisitionMetrics): void {
        const discovered = this.total(m, 'inspecto_files_discovered_total');
        const downloaded = this.total(m, 'inspecto_files_downloaded_total');
        const failed = this.total(m, 'inspecto_downloads_failed_total');
        const skipped = this.total(m, 'inspecto_watermark_skipped_total');
        const bytes = this.total(m, 'inspecto_bytes_transferred_total');
        const active = this.total(m, 'inspecto_active_connections');

        this.cards = [
            { label: 'Files discovered', value: this.fmtInt(discovered) },
            { label: 'Files downloaded', value: this.fmtInt(downloaded) },
            { label: 'Downloads failed', value: this.fmtInt(failed) },
            { label: 'Watermark skipped', value: this.fmtInt(skipped) },
            { label: 'Bytes transferred', value: this.fmtBytes(bytes) },
            { label: 'Active connections', value: this.fmtInt(active) },
        ];

        this.discoveredData = {
            labels: ['Discovered', 'Downloaded', 'Failed'],
            datasets: [
                {
                    label: 'files',
                    data: [discovered, downloaded, failed],
                    backgroundColor: [CHART_SERIES.primary, CHART_SERIES.success, CHART_SERIES.error],
                },
            ],
        };
    }

    private fmtInt(n: number): string {
        return Math.round(n).toLocaleString();
    }

    private fmtBytes(n: number): string {
        if (n < 1024) return `${Math.round(n)} B`;
        const units = ['KB', 'MB', 'GB', 'TB'];
        let v = n / 1024;
        let i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return `${v.toFixed(1)} ${units[i]}`;
    }

    async trigger(source: SourceView): Promise<void> {
        if (!(await this.confirm.confirm(`Run pipeline "${source.pipeline}" now?`, 'Run now'))) return;
        this.pipelines.trigger(source.pipeline).subscribe({
            next: (r) => {
                const msg = `${source.pipeline}: ${r.total} processed, ${r.failed} failed`;
                r.failed ? this.toastr.warning(msg) : this.toastr.success(msg);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Run failed for ${source.pipeline}`)),
        });
    }

    openDetail(source: SourceView): void {
        this.dialog.open(SourceDetailDialog, { data: source, width: '680px', maxHeight: '85vh' });
    }

    readonly refreshActions = refreshActionsCells;
}
