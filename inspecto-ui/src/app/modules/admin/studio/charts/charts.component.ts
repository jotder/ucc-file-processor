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
import { Chart } from './chart-types';
import { ChartsService } from './charts.service';

/**
 * Studio **Charts** — saved visualizations (the `chart` kind). Lists charts with their viz type + dataset and
 * links to the explore workbench. Mirrors `DatasetsComponent`.
 */
@Component({
    selector: 'app-charts',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        RouterLink,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './charts.component.html',
})
export class ChartsComponent implements OnInit {
    private api = inject(ChartsService);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    readonly charts = signal<Chart[]>([]);
    readonly loading = signal(false);
    readonly filterText = signal('');

    readonly visibleCharts = computed(() => {
        const q = this.filterText().trim().toLowerCase();
        const all = this.charts();
        if (!q) return all;
        return all.filter((c) => [c.id, c.vizType, c.datasetId].join(' ').toLowerCase().includes(q));
    });

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.list().subscribe({
            next: (c) => {
                this.charts.set(c);
                this.loading.set(false);
            },
            error: () => {
                this.charts.set([]);
                this.loading.set(false);
                this.toastr.warning('Could not load charts — is ControlApi running?');
            },
        });
    }

    onFilter(ev: Event): void {
        this.filterText.set((ev.target as HTMLInputElement).value);
    }

    async remove(c: Chart): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete chart "${c.id}"?`, { title: 'Delete chart' }))) return;
        this.api.remove(c.id).subscribe({
            next: () => {
                this.toastr.success(`Chart "${c.id}" deleted`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not delete "${c.id}".`)),
        });
    }
}
