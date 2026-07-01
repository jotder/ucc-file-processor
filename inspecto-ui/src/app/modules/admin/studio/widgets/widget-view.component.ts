import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { WidgetHostComponent } from './widget-host.component';

/**
 * Standalone widget view (`/studio/widgets/:id/view`) — the same {@link WidgetHostComponent} render path as a
 * dashboard tile or a gallery thumbnail, just full-page. Self-fetch mode: only the route id is known here.
 */
@Component({
    selector: 'app-widget-view',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, RouterLink, WidgetHostComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex min-w-0 flex-auto flex-col p-6 md:p-8">
            <nav class="text-secondary mb-1 text-sm" aria-label="Breadcrumb">
                <a routerLink="/studio/widgets" class="hover:underline">Widgets</a>
                <span class="px-1">/</span>
                <span>{{ id }}</span>
            </nav>
            <div class="flex items-center justify-between">
                <h1 class="text-3xl font-extrabold leading-tight tracking-tight">{{ id }}</h1>
                <a mat-stroked-button [routerLink]="['/studio/widgets', id]">
                    <mat-icon svgIcon="heroicons_outline:pencil-square"></mat-icon>
                    <span class="ml-2">Edit</span>
                </a>
            </div>
            <div class="mt-6 max-w-2xl">
                <app-widget-host [widgetId]="id" />
            </div>
        </div>
    `,
})
export class WidgetViewComponent {
    /** Route param — the widget id to view. */
    @Input() id!: string;
}
