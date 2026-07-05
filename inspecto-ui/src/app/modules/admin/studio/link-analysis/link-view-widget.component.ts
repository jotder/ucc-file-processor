import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { G6GraphData } from 'app/inspecto/graph';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';
import { LinkAnalysisService, LinkAnalysisView } from './link-analysis.service';
import { GraphSourcesService } from './graph-sources';

/**
 * Read-only **Link analysis widget** host (Phase 4): renders a saved `link-analysis-view` Component on a
 * dashboard tile by re-running the view's own GraphSource query and feeding the shared
 * {@link GraphViewComponent} with the captured display options + layout. Loaded lazily through the viz
 * component-loader registry (`widget.kind`), so G6 never joins the eager dashboard bundle. The widget is a
 * viewer; investigating happens at `/studio/link-analysis`.
 */
@Component({
    selector: 'app-link-view-widget',
    standalone: true,
    imports: [GraphViewComponent, InspectoAlertComponent, InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex h-full min-h-0 flex-col">
            @if (error(); as message) {
                <inspecto-alert class="block" variant="warning">{{ message }}</inspecto-alert>
            } @else if (data(); as d) {
                <inspecto-graph-view
                    class="min-h-0 flex-auto"
                    [data]="d"
                    [display]="view()?.display ?? null"
                    [layout]="view()?.layout ?? null"
                    [fill]="true"
                />
            } @else if (loaded()) {
                <inspecto-empty-state icon="heroicons_outline:share" message="No saved Link-Analysis view bound to this widget." />
            } @else {
                <div class="text-secondary flex h-full items-center justify-center text-sm">Loading…</div>
            }
        </div>
    `,
})
export class LinkViewWidgetComponent {
    private linkAnalysisApi = inject(LinkAnalysisService);
    private graphSources = inject(GraphSourcesService);

    /** The saved `link-analysis-view` id this widget renders (the widget's binding). */
    readonly viewId = input<string | undefined>(undefined);

    readonly view = signal<LinkAnalysisView | null>(null);
    readonly data = signal<G6GraphData | null>(null);
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
            this.linkAnalysisApi.get(id).subscribe({
                next: (view) => {
                    this.view.set(view);
                    this.loaded.set(true);
                    if (!view) return;
                    const source = this.graphSources.byId(view.sourceId);
                    if (!source) {
                        this.error.set(`Unknown graph source “${view.sourceId}”.`);
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
