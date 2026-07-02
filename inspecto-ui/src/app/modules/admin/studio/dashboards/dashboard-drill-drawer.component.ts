import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DataTableComponent } from 'app/inspecto/data-table';

/**
 * Dashboard **drill-through drawer** — a slide-over showing the underlying rows behind a tile, with the
 * dashboard's live cross-filter already applied (the host evaluates the filter and passes the rows). Reuses
 * the standard-tier data table (column chooser / search / CSV export) rather than re-rolling a grid.
 * Presentational; the host owns open/close state and the row evaluation.
 */
@Component({
    selector: 'app-dashboard-drill-drawer',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule, DataTableComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div
            class="bg-card fixed inset-y-0 right-0 z-50 flex w-full max-w-3xl flex-col gap-3 overflow-y-auto p-6 shadow-2xl"
            role="dialog"
            aria-modal="true"
            [attr.aria-label]="'Rows behind ' + title()"
        >
            <div class="flex items-start justify-between gap-2">
                <div class="min-w-0">
                    <h2 class="truncate text-xl font-bold">{{ title() }}</h2>
                    <div class="text-secondary text-sm">{{ rows().length }} row(s) after the dashboard filter</div>
                </div>
                <button mat-icon-button (click)="closed.emit()" matTooltip="Close" aria-label="Close drill-through">
                    <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                </button>
            </div>
            <inspecto-data-table
                tier="standard"
                [rows]="rows()"
                [sourceName]="sourceName()"
                [exportName]="sourceName() + '-rows'"
                [autoHeight]="true"
                noRowsTitle="No rows match the current filter"
            />
        </div>
    `,
})
export class DashboardDrillDrawerComponent {
    readonly title = input.required<string>();
    readonly sourceName = input.required<string>();
    readonly rows = input.required<Record<string, unknown>[]>();
    readonly closed = output<void>();
}
