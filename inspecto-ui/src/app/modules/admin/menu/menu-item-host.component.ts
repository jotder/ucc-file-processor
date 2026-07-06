import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map, of, switchMap } from 'rxjs';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { MenuBinding, MenuService } from 'app/inspecto/menu';
// Canonical render hosts (they self-fetch by id and lazy-load heavy deps via the viz registry). These
// live under studio/ but are render components meant to be embedded — the same ones dashboard tiles use.
import { Dashboard } from 'app/modules/admin/studio/dashboards/dashboard-types';
import { DashboardsService } from 'app/modules/admin/studio/dashboards/dashboards.service';
import { GeoViewWidgetComponent } from 'app/modules/admin/studio/geo-map/geo-view-widget.component';
import { LinkViewWidgetComponent } from 'app/modules/admin/studio/link-analysis/link-view-widget.component';
import { WidgetHostComponent } from 'app/modules/admin/studio/widgets/widget-host.component';
import 'app/modules/admin/studio/widgets/widget.kind'; // side-effect: register viz plugins + geo/link view loaders

/**
 * Dynamic host for a Menu item — the single parameterized route (`/w/:nodeId`) every custom menu leaf
 * links to. Resolves the node's {@link MenuBinding} from {@link MenuService} and renders the bound library
 * artifact through its canonical by-id render host: a Widget via `app-widget-host`, a saved view via the
 * geo / link view widgets, a Dashboard by laying its tiles out as widget hosts. An unknown node or an
 * unbound leaf falls back to the shared empty state. See docs/superpower/menu-builder-plan.md (M3).
 */
@Component({
    selector: 'app-menu-item-host',
    standalone: true,
    imports: [
        InspectoEmptyStateComponent,
        WidgetHostComponent,
        GeoViewWidgetComponent,
        LinkViewWidgetComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex min-w-0 flex-auto flex-col p-6 md:p-8">
            @if (node(); as n) {
                <h1 class="mb-4 text-2xl font-extrabold leading-tight tracking-tight">{{ n.title }}</h1>

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
                        message="This menu is a group — pick one of its items, or link a report to it in the Menu Builder."
                    />
                }
            } @else {
                <inspecto-empty-state
                    icon="heroicons_outline:question-mark-circle"
                    title="Menu item not found"
                    message="This link points to a menu item that no longer exists."
                />
            }
        </div>
    `,
})
export class MenuItemHostComponent {
    private readonly route = inject(ActivatedRoute);
    private readonly menu = inject(MenuService);
    private readonly dashboards = inject(DashboardsService);

    private readonly nodeId = toSignal(this.route.paramMap.pipe(map((p) => p.get('nodeId') ?? '')), {
        initialValue: '',
    });

    readonly node = computed(() => this.menu.find(this.nodeId()));
    readonly binding = computed<MenuBinding | undefined>(() => this.node()?.binding);

    /** Fetch the Dashboard (tiles) only when the binding is a dashboard. */
    readonly dashboard = toSignal<Dashboard | null>(
        toObservable(this.binding).pipe(
            switchMap((b) => (b?.kind === 'dashboard' ? this.dashboards.get(b.componentId) : of(null))),
        ),
        { initialValue: null },
    );
}
