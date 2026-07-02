import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { Router, RouterLink } from '@angular/router';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { Dashboard } from '../studio/dashboards/dashboard-types';
import { DashboardsService } from '../studio/dashboards/dashboards.service';

/**
 * KPI & Reports — the read-only landing gallery over the Studio dashboards: every saved dashboard as a card
 * (tile count + quick-filter count) opening in the Studio editor. Replaces the "coming soon" placeholder;
 * authoring stays in Studio.
 */
@Component({
    standalone: true,
    imports: [MatIconModule, RouterLink, InspectoEmptyStateComponent, InspectoSkeletonComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex min-w-0 flex-auto flex-col p-6 sm:p-8">
            <div class="mb-6 flex items-center gap-3">
                <mat-icon class="text-primary icon-size-8" svgIcon="heroicons_outline:document-chart-bar"></mat-icon>
                <h1 class="text-2xl font-semibold tracking-tight">KPI &amp; Reports</h1>
            </div>

            @if (loading()) {
                <inspecto-skeleton [lines]="3" height="4rem" />
            } @else if (dashboards().length === 0) {
                <inspecto-empty-state
                    icon="heroicons_outline:document-chart-bar"
                    title="No dashboards yet"
                    message="Build a dashboard in Studio and it will appear here."
                    actionLabel="Create a dashboard"
                    (action)="createDashboard()"
                />
            } @else {
                <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                    @for (d of dashboards(); track d.id) {
                        <a
                            class="bg-card flex flex-col gap-1 rounded-2xl p-5 shadow transition-shadow hover:shadow-md"
                            [routerLink]="['/studio/dashboards', d.id]"
                        >
                            <div class="flex items-center gap-2">
                                <mat-icon class="text-primary icon-size-5" svgIcon="heroicons_outline:squares-2x2"></mat-icon>
                                <span class="truncate font-semibold">{{ d.name }}</span>
                            </div>
                            <span class="text-secondary text-sm">
                                {{ d.tiles.length }} tile(s){{ d.exposedFields?.length ? ' · ' + d.exposedFields!.length + ' quick filter(s)' : '' }}
                            </span>
                        </a>
                    }
                </div>
            }
        </div>
    `,
})
export class KpiReportsComponent implements OnInit {
    private dashboardsApi = inject(DashboardsService);
    private router = inject(Router);

    createDashboard(): void {
        this.router.navigate(['/studio/dashboards/new']);
    }

    readonly dashboards = signal<Dashboard[]>([]);
    readonly loading = signal(true);

    ngOnInit(): void {
        this.dashboardsApi.list().subscribe({
            next: (d) => {
                this.dashboards.set(d);
                this.loading.set(false);
            },
            error: () => this.loading.set(false),
        });
    }
}
