import { Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';

@Component({
    standalone: true,
    imports: [MatIconModule, InspectoEmptyStateComponent],
    template: `
        <div class="absolute inset-0 flex flex-col p-6 sm:p-8">
            <div class="flex items-center gap-3 mb-6">
                <mat-icon class="icon-size-8 text-primary" svgIcon="heroicons_outline:document-chart-bar"></mat-icon>
                <h1 class="text-2xl font-semibold tracking-tight">KPI &amp; Reports</h1>
            </div>
            <inspecto-empty-state
                icon="heroicons_outline:document-chart-bar"
                title="KPI & Reports"
                message="Define, track and visualise key performance indicators across your pipelines. Coming soon.">
            </inspecto-empty-state>
        </div>
    `,
})
export class KpiReportsComponent {}
