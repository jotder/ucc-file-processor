import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { AddToDashboardDialog, AddToDashboardResult } from './add-to-dashboard.dialog';
import { Widget } from './widget-types';
import { WidgetsService } from './widgets.service';
import { WidgetHostComponent } from './widget-host.component';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { Dashboard } from '../dashboards/dashboard-types';
import { DashboardsService } from '../dashboards/dashboards.service';

/**
 * Studio **Viz Library** — the reusable widget library (the `widget` kind). A searchable gallery (text +
 * viz type + tags) with a live-render thumbnail per card (the shared {@link WidgetHostComponent}), and
 * per-card actions: standalone view, edit in the Widget Builder, place on a dashboard. Mirrors
 * `DatasetsComponent`.
 */
@Component({
    selector: 'app-widgets',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        RouterLink,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
        WidgetHostComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './widgets.component.html',
})
export class WidgetsComponent implements OnInit {
    private api = inject(WidgetsService);
    private datasetsApi = inject(DatasetsService);
    private dashboardsApi = inject(DashboardsService);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    readonly widgets = signal<Widget[]>([]);
    readonly datasets = signal<Dataset[]>([]);
    readonly loading = signal(false);
    readonly filterText = signal('');
    /** The clicked tag, if any — narrows the gallery to widgets carrying it. Click again to clear. */
    readonly activeTag = signal<string | null>(null);
    /** The clicked viz type, if any — narrows the gallery to that type. Click again to clear. */
    readonly activeType = signal<string | null>(null);

    readonly datasetsById = computed(() => new Map(this.datasets().map((d) => [d.id, d])));

    /** Every tag across all widgets, for the filter chip row. */
    readonly allTags = computed(() => [...new Set(this.widgets().flatMap((w) => w.tags ?? []))].sort());

    /** Every viz type across all widgets, for the type chip row. */
    readonly allTypes = computed(() => [...new Set(this.widgets().map((w) => w.vizType))].sort());

    readonly visibleWidgets = computed(() => {
        const q = this.filterText().trim().toLowerCase();
        const tag = this.activeTag();
        const type = this.activeType();
        let all = this.widgets();
        if (type) all = all.filter((w) => w.vizType === type);
        if (tag) all = all.filter((w) => w.tags?.includes(tag));
        if (!q) return all;
        return all.filter((w) =>
            [w.id, w.vizType, w.datasetId, w.viewId ?? '', w.description ?? '', ...(w.tags ?? [])].join(' ').toLowerCase().includes(q),
        );
    });

    ngOnInit(): void {
        this.load();
        this.datasetsApi.list().subscribe({ next: (d) => this.datasets.set(d), error: () => undefined });
    }

    load(): void {
        this.loading.set(true);
        this.api.list().subscribe({
            next: (w) => {
                this.widgets.set(w);
                this.loading.set(false);
            },
            error: () => {
                this.widgets.set([]);
                this.loading.set(false);
                this.toastr.warning('Could not load widgets — is ControlApi running?');
            },
        });
    }

    onFilter(ev: Event): void {
        this.filterText.set((ev.target as HTMLInputElement).value);
    }

    toggleTag(tag: string): void {
        this.activeTag.set(this.activeTag() === tag ? null : tag);
    }

    toggleType(type: string): void {
        this.activeType.set(this.activeType() === type ? null : type);
    }

    /** Place the widget on an existing dashboard or a newly created one, then open it in the Dashboard Builder. */
    addToDashboard(w: Widget): void {
        this.dashboardsApi.list().subscribe({
            next: (dashboards) => {
                this.dialog
                    .open(AddToDashboardDialog, { data: { widgetId: w.id, dashboards }, width: '28rem' })
                    .afterClosed()
                    .subscribe((res: AddToDashboardResult | undefined) => {
                        if (!res) return;
                        if (res.newName) this.placeOnNew(w, res.newName);
                        else if (res.existingId) this.placeOnExisting(w, res.existingId);
                    });
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not load dashboards.')),
        });
    }

    private placeOnNew(w: Widget, name: string): void {
        const dashboard: Dashboard = { id: name, name, tiles: [{ widgetId: w.id, span: 1 }], filter: null };
        this.dashboardsApi.save(dashboard).subscribe({
            next: () => this.openDashboard(w, name),
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not create dashboard "${name}".`)),
        });
    }

    private placeOnExisting(w: Widget, id: string): void {
        this.dashboardsApi.get(id).subscribe({
            next: (dashboard) => {
                dashboard.tiles = [...dashboard.tiles, { widgetId: w.id, span: 1 }];
                this.dashboardsApi.save(dashboard).subscribe({
                    next: () => this.openDashboard(w, id),
                    error: (e) => this.toastr.error(apiErrorMessage(e, `Could not update dashboard "${id}".`)),
                });
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load dashboard "${id}".`)),
        });
    }

    private openDashboard(w: Widget, dashboardId: string): void {
        this.toastr.success(`Widget "${w.id}" placed on dashboard "${dashboardId}"`);
        this.router.navigate(['/studio/dashboards', dashboardId]);
    }

    async remove(w: Widget): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete widget "${w.id}"?`, { title: 'Delete widget' }))) return;
        this.api.remove(w.id).subscribe({
            next: () => {
                this.toastr.success(`Widget "${w.id}" deleted`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not delete "${w.id}".`)),
        });
    }
}
