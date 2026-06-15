import { Component, inject, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { Subscription } from 'rxjs';
import {
    EVENT_LEVELS,
    EVENT_TYPES,
    EventFilter,
    EventRow,
    EventsService,
    SavedEventView,
    visibleInterval,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import {
    actionsColumn,
    fmtDateTime,
    INSPECTO_DEFAULT_COL_DEF,
    InspectoGridThemeService,
    refreshActionsCells,
} from 'app/inspecto/grid';
import { ToastrService } from 'ngx-toastr';
import { EventDetailDialog, EventDrilldown } from './event-detail.dialog';

/** Live-tail poll cadence (ms) — pauses while the tab is hidden via {@link visibleInterval}. */
const LIVE_TAIL_MS = 5000;

/**
 * Events / Activity — the Operational Intelligence event stream (GET /events/search), newest-first. A
 * filter toolbar (minimum level, type, pipeline, free-text) drives the query; a live-tail toggle polls
 * while the tab is visible; matching rows export to CSV (GET /events/export); and operator-saved views
 * persist filter sets (GET/POST /events/views). Row detail opens the full event + attributes, from which
 * an operator can drill into a correlation id or event type.
 */
@Component({
    selector: 'app-events',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatMenuModule,
        MatSelectModule,
        MatSlideToggleModule,
        MatTooltipModule,
        AgGridAngular,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './events.component.html',
    encapsulation: ViewEncapsulation.None,
})
export class EventsComponent implements OnInit, OnDestroy {
    private api = inject(EventsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    readonly themeSvc = inject(InspectoGridThemeService);

    readonly levels = EVENT_LEVELS;
    readonly types = EVENT_TYPES;
    readonly limitOptions = [50, 100, 250, 500, 1000];

    events: EventRow[] = [];
    loading = false;
    live = false;
    private liveSub?: Subscription;

    // ── filter toolbar ─────────────────────────────────────────────────────────
    fLevel = '';
    fType = '';
    fPipeline = '';
    fq = '';
    fLimit = 100;
    /** Set only via the detail dialog "View related" drill-down; surfaced as a removable chip. */
    fCorrelation = '';

    // ── saved views ────────────────────────────────────────────────────────────
    views: SavedEventView[] = [];
    selectedView = '';
    saveName = '';

    readonly defaultColDef = INSPECTO_DEFAULT_COL_DEF;
    readonly columnDefs: ColDef<EventRow>[] = [
        { headerName: 'Time', width: 180, valueGetter: (p) => p.data?.ts, valueFormatter: (p) => fmtDateTime(p.value) },
        {
            field: 'level',
            headerName: 'Level',
            width: 96,
            cellRenderer: (p: ICellRendererParams<EventRow>) => statusBadgeHtml(p.value as string),
        },
        { field: 'type', headerName: 'Type', width: 180 },
        { field: 'pipeline', headerName: 'Pipeline', width: 140, valueFormatter: (p) => p.value ?? '—' },
        { field: 'correlationId', headerName: 'Correlation', width: 150, valueFormatter: (p) => p.value ?? '—' },
        { field: 'source', headerName: 'Source', flex: 1, minWidth: 160 },
        { field: 'message', headerName: 'Message', flex: 2, minWidth: 220 },
        actionsColumn<EventRow>([
            {
                icon: 'heroicons_outline:information-circle',
                hint: 'Details',
                onClick: (e) => this.openDetail(e),
            },
        ], 80),
    ];

    ngOnInit(): void {
        this.load();
        this.loadViews();
    }

    ngOnDestroy(): void {
        this.liveSub?.unsubscribe();
    }

    private buildFilter(): EventFilter {
        return {
            level: this.fLevel || undefined,
            type: this.fType || undefined,
            pipeline: this.fPipeline.trim() || undefined,
            correlationId: this.fCorrelation || undefined,
            q: this.fq.trim() || undefined,
            limit: this.fLimit,
        };
    }

    /** Run the current query. `silent` (live-tail tick) keeps the grid visible instead of flashing the loader. */
    load(silent = false): void {
        if (!silent) this.loading = true;
        this.api.search(this.buildFilter()).subscribe({
            next: (rows) => {
                this.events = rows;
                this.loading = false;
            },
            error: () => {
                this.loading = false;
                if (!silent) {
                    this.events = [];
                    this.toastr.error('Failed to load events');
                }
            },
        });
    }

    resetFilters(): void {
        this.fLevel = '';
        this.fType = '';
        this.fPipeline = '';
        this.fq = '';
        this.fLimit = 100;
        this.fCorrelation = '';
        this.selectedView = '';
        this.load();
    }

    clearCorrelation(): void {
        this.fCorrelation = '';
        this.load();
    }

    toggleLive(on: boolean): void {
        this.live = on;
        this.liveSub?.unsubscribe();
        this.liveSub = undefined;
        if (on) this.liveSub = visibleInterval(LIVE_TAIL_MS).subscribe(() => this.load(true));
    }

    openDetail(row: EventRow): void {
        this.dialog
            .open(EventDetailDialog, { data: row, width: '640px', maxHeight: '85vh' })
            .afterClosed()
            .subscribe((d?: EventDrilldown) => {
                if (!d) return;
                if (d.correlationId) this.fCorrelation = d.correlationId;
                if (d.type) this.fType = d.type;
                this.load();
            });
    }

    exportCsv(): void {
        this.api.exportCsv(this.buildFilter()).subscribe({
            next: (csv) => {
                const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `events-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`;
                a.click();
                URL.revokeObjectURL(url);
            },
            error: () => this.toastr.error('Export failed'),
        });
    }

    // ── saved views ────────────────────────────────────────────────────────────

    private loadViews(): void {
        this.api.views().subscribe({
            next: (v) => (this.views = v),
            error: () => (this.views = []),
        });
    }

    applyView(name: string): void {
        this.selectedView = name;
        const v = this.views.find((x) => x.name === name);
        if (!v) return;
        const f = v.filters ?? {};
        this.fLevel = f['level'] ?? '';
        this.fType = f['type'] ?? '';
        this.fPipeline = f['pipeline'] ?? '';
        this.fCorrelation = f['correlationId'] ?? '';
        this.fq = f['q'] ?? '';
        this.load();
    }

    saveView(): void {
        const name = this.saveName.trim();
        if (!name) {
            this.toastr.error('Enter a view name');
            return;
        }
        const filters: Record<string, string> = {};
        for (const [k, v] of Object.entries(this.buildFilter())) {
            if (v !== undefined && k !== 'limit' && k !== 'offset') filters[k] = String(v);
        }
        this.api.saveView(name, filters).subscribe({
            next: () => {
                this.toastr.success(`Saved view "${name}"`);
                this.saveName = '';
                this.selectedView = name;
                this.loadViews();
            },
            error: () => this.toastr.error('Save failed'),
        });
    }

    async deleteView(): Promise<void> {
        if (!this.selectedView) {
            this.toastr.error('Select a saved view first');
            return;
        }
        const name = this.selectedView;
        if (!(await this.confirm.confirmDestructive(`Delete saved view "${name}"?`, { title: 'Delete view' }))) return;
        this.api.deleteView(name).subscribe({
            next: () => {
                this.toastr.success(`Deleted "${name}"`);
                this.selectedView = '';
                this.loadViews();
            },
            error: () => this.toastr.error('Delete failed'),
        });
    }

    readonly refreshActions = refreshActionsCells;
}
