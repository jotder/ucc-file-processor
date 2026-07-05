import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { GeoData, MapViewComponent } from 'app/inspecto/geo';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { GeoMapService, GeoMapView } from './geo-map.service';
import { GeoSourcesService } from './geo-projection';

/**
 * Read-only **Geo map widget** host (Phase 4): renders a saved `geo-map-view` Component on a dashboard tile
 * by re-running the view's own GeoSource query and feeding the shared {@link MapViewComponent} with the
 * captured display mode + camera. Loaded lazily through the viz component-loader registry (`widget.kind`),
 * so MapLibre never joins the eager dashboard bundle. Investigation notes/overlays stay studio-only — the
 * widget is a viewer; editing happens at `/studio/geo-map`.
 */
@Component({
    selector: 'app-geo-view-widget',
    standalone: true,
    imports: [MapViewComponent, InspectoAlertComponent, InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex h-full min-h-0 flex-col">
            @if (error(); as message) {
                <inspecto-alert class="block" variant="warning">{{ message }}</inspecto-alert>
            } @else if (data(); as d) {
                <inspecto-map-view
                    class="min-h-0 flex-auto"
                    [data]="d"
                    [display]="view()?.display ?? 'markers'"
                    [camera]="view()?.camera ?? null"
                    [fill]="true"
                />
            } @else if (loaded()) {
                <inspecto-empty-state icon="heroicons_outline:map" message="No saved Geo view bound to this widget." />
            } @else {
                <div class="text-secondary flex h-full items-center justify-center text-sm">Loading…</div>
            }
        </div>
    `,
})
export class GeoViewWidgetComponent {
    private geoMapApi = inject(GeoMapService);
    private geoSources = inject(GeoSourcesService);

    /** The saved `geo-map-view` id this widget renders (the widget's binding). */
    readonly viewId = input<string | undefined>(undefined);

    readonly view = signal<GeoMapView | null>(null);
    readonly data = signal<GeoData | null>(null);
    readonly error = signal<string | null>(null);
    /** The view fetch settled (found or not) — gates the not-found empty state vs the loading strip. */
    readonly loaded = signal(false);

    constructor() {
        effect(() => {
            const id = this.viewId();
            this.view.set(null);
            this.data.set(null);
            this.error.set(null);
            this.loaded.set(false);
            if (!id) {
                this.loaded.set(true);
                return;
            }
            this.geoMapApi.get(id).subscribe({
                next: (view) => {
                    this.view.set(view);
                    this.loaded.set(true);
                    if (!view) return;
                    const source = this.geoSources.sources.find((s) => s.id === view.sourceId);
                    if (!source) {
                        this.error.set(`Unknown geo source “${view.sourceId}”.`);
                        return;
                    }
                    source
                        .query(view.query)
                        .then((d) => this.data.set(d))
                        .catch((e: unknown) => this.error.set(e instanceof Error ? e.message : 'The view’s query failed.'));
                },
                error: () => {
                    this.loaded.set(true);
                    this.error.set(`Could not load the saved view “${id}”.`);
                },
            });
        });
    }
}
