import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { Dashboard } from './dashboard-types';
import { DashboardsService } from './dashboards.service';

/**
 * Studio **Dashboard Builder** — saved compositions of widgets (the `dashboard` composite kind). Lists dashboards with
 * their tile count and links to the grid editor. Mirrors `WidgetsComponent`.
 */
@Component({
    selector: 'app-dashboards',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        RouterLink,
        InspectoEmptyStateComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './dashboards.component.html',
})
export class DashboardsComponent implements OnInit {
    private api = inject(DashboardsService);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    readonly dashboards = signal<Dashboard[]>([]);
    readonly loading = signal(false);
    readonly filterText = signal('');

    readonly visibleDashboards = computed(() => {
        const q = this.filterText().trim().toLowerCase();
        const all = this.dashboards();
        return q ? all.filter((d) => d.id.toLowerCase().includes(q)) : all;
    });

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.list().subscribe({
            next: (d) => {
                this.dashboards.set(d);
                this.loading.set(false);
            },
            error: () => {
                this.dashboards.set([]);
                this.loading.set(false);
                this.toastr.warning('Could not load dashboards — is ControlApi running?');
            },
        });
    }

    onFilter(ev: Event): void {
        this.filterText.set((ev.target as HTMLInputElement).value);
    }

    async remove(d: Dashboard): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete dashboard "${d.id}"?`, { title: 'Delete dashboard' }))) return;
        this.api.remove(d.id).subscribe({
            next: () => {
                this.toastr.success(`Dashboard "${d.id}" deleted`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not delete "${d.id}".`)),
        });
    }
}
