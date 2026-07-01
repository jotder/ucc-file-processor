import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { Widget } from './widget-types';
import { WidgetsService } from './widgets.service';
import { WidgetHostComponent } from './widget-host.component';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';

/**
 * Studio **Widgets** — the reusable widget library (the `widget` kind). A searchable, taggable gallery with a
 * live-render thumbnail per card (the shared {@link WidgetHostComponent}), and links to the explore workbench
 * (edit) or the standalone view. Mirrors `DatasetsComponent`.
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
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    readonly widgets = signal<Widget[]>([]);
    readonly datasets = signal<Dataset[]>([]);
    readonly loading = signal(false);
    readonly filterText = signal('');
    /** The clicked tag, if any — narrows the gallery to widgets carrying it. Click again to clear. */
    readonly activeTag = signal<string | null>(null);

    readonly datasetsById = computed(() => new Map(this.datasets().map((d) => [d.id, d])));

    /** Every tag across all widgets, for the filter chip row. */
    readonly allTags = computed(() => [...new Set(this.widgets().flatMap((w) => w.tags ?? []))].sort());

    readonly visibleWidgets = computed(() => {
        const q = this.filterText().trim().toLowerCase();
        const tag = this.activeTag();
        let all = this.widgets();
        if (tag) all = all.filter((w) => w.tags?.includes(tag));
        if (!q) return all;
        return all.filter((w) => [w.id, w.vizType, w.datasetId, ...(w.tags ?? [])].join(' ').toLowerCase().includes(q));
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
