import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { of, switchMap } from 'rxjs';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { MenuBinding } from 'app/inspecto/menu';
// Canonical render hosts (self-fetch by id, lazy-load heavy deps via the viz registry) — the same ones
// dashboard tiles use. They live under studio/ but are render components meant to be embedded.
import { Dashboard } from 'app/modules/admin/studio/dashboards/dashboard-types';
import { DashboardsService } from 'app/modules/admin/studio/dashboards/dashboards.service';
import { GeoViewWidgetComponent } from 'app/modules/admin/studio/geo-map/geo-view-widget.component';
import { LinkViewWidgetComponent } from 'app/modules/admin/studio/link-analysis/link-view-widget.component';
import { WidgetHostComponent } from 'app/modules/admin/studio/widgets/widget-host.component';
import 'app/modules/admin/studio/widgets/widget.kind'; // side-effect: register viz plugins + geo/link view loaders

/**
 * Presentational renderer for a Menu item's {@link MenuBinding} — the shared render surface for both the
 * dynamic `/w/:nodeId` host and the Menu Builder's live preview. A Widget / saved view renders through its
 * canonical by-id host; a Dashboard lays its tiles out as widget hosts. No binding → the shared empty state.
 */
@Component({
    selector: 'app-menu-artifact',
    standalone: true,
    imports: [
        InspectoEmptyStateComponent,
        WidgetHostComponent,
        GeoViewWidgetComponent,
        LinkViewWidgetComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (binding(); as b) {
            @switch (b.kind) {
                @case ('widget') {
                    <app-widget-host class="block" [widgetId]="b.componentId" />
                }
                @case ('geo-map-view') {
                    <div class="h-[70vh] min-h-0"><app-geo-view-widget [viewId]="b.componentId" /></div>
                }
                @case ('link-analysis-view') {
                    <div class="h-[70vh] min-h-0"><app-link-view-widget [viewId]="b.componentId" /></div>
                }
                @case ('dashboard') {
                    @if (dashboard(); as d) {
                        <div class="flex flex-wrap gap-4">
                            @for (tile of d.tiles; track tile.widgetId) {
                                <div
                                    class="min-w-0 flex-grow"
                                    [style.flex-basis]="tile.span === 2 ? '100%' : 'calc(50% - 0.5rem)'"
                                >
                                    <app-widget-host [widgetId]="tile.widgetId" />
                                </div>
                            }
                        </div>
                    } @else {
                        <div class="text-secondary flex h-40 items-center justify-center text-sm">Loading…</div>
                    }
                }
            }
        } @else {
            <inspecto-empty-state
                icon="heroicons_outline:queue-list"
                title="Nothing linked yet"
                [message]="emptyMessage()"
            />
        }
    `,
})
export class MenuArtifactComponent {
    private readonly dashboards = inject(DashboardsService);

    readonly binding = input<MenuBinding | undefined>(undefined);
    readonly emptyMessage = input('This item isn’t linked to a report yet.');

    /** Fetch the Dashboard (tiles) only when the binding is a dashboard. */
    readonly dashboard = toSignal<Dashboard | null>(
        toObservable(computed(() => this.binding())).pipe(
            switchMap((b) => (b?.kind === 'dashboard' ? this.dashboards.get(b.componentId) : of(null))),
        ),
        { initialValue: null },
    );
}
