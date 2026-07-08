import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { PublicMeasure, PublicQueryBody, ShareService, SharedDashboard, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { VizRenderComponent } from 'app/inspecto/viz/viz-render.component';
// Side-effect import: registers the built-in VizPlugins. Essential here — /share/:token is a guest,
// shell-less route, so nothing else has loaded the registry when a share link is opened directly.
import 'app/inspecto/viz/plugins';
import { getViz } from 'app/inspecto/viz/viz-registry';
import { channelMeasureId } from 'app/inspecto/viz/query-spec';
import { ChannelValue, ControlValues, VizPlugin, VizProps } from 'app/inspecto/viz/viz-types';

/** The subset of a widget component's content the embed needs (structurally a Studio `WidgetConfig`). */
interface EmbedWidget {
    name?: string;
    datasetId: string;
    vizType: string;
    controls: ControlValues;
    viewId?: string;
}

/** One rendered tile's view-model; `state` drives the per-tile skeleton/alert/viz switch. */
interface TileVm {
    title: string;
    span: 1 | 2;
    state: 'loading' | 'ready' | 'unsupported' | 'error';
    detail?: string;
    plugin?: VizPlugin;
    props?: VizProps;
}

/**
 * Derive the public-query body for one widget (BI-6): the plugin's own `buildQuery` picks which
 * channels count (per-plugin semantics preserved), then each compiled measure id is mapped back to its
 * raw `{agg, field}` pair through the controls — the backend accepts validated identifiers, never SQL
 * text. Returns `null` when the widget is not embeddable: a saved-view binding (`viewId`) has no query,
 * and a named-measure `expression` cannot cross the wire.
 */
export function embedQueryBody(widget: EmbedWidget): PublicQueryBody | null {
    if (widget.viewId) return null;
    const plugin = getViz(widget.vizType);
    if (!plugin) return null;
    const spec = plugin.buildQuery(widget.controls ?? {}, {
        datasetId: widget.datasetId, sourceName: widget.datasetId, filters: null,
    });
    const byId = new Map<string, ChannelValue>();
    for (const values of Object.values(widget.controls ?? {})) {
        for (const cv of values ?? []) byId.set(channelMeasureId(cv), cv);
    }
    const measures: PublicMeasure[] = [];
    for (const m of spec.measures) {
        const cv = byId.get(m.id);
        if (!cv || cv.expression) return null;   // expression measures are not embeddable (no SQL crosses)
        measures.push(cv.agg === 'count' ? { agg: 'count' } : { agg: cv.agg ?? 'sum', field: cv.field });
    }
    const body: PublicQueryBody = { dataset: widget.datasetId };
    if (measures.length) body.measures = measures;
    if (spec.groupBy.length) body.groupBy = spec.groupBy;
    if (spec.orderBy?.length) body.orderBy = spec.orderBy;
    body.limit = spec.limit ?? 500;
    return body;
}

/**
 * The public dashboard embed viewer (BI-6) — a guest, shell-less route (`/share/:token`; no auth guard,
 * no admin layout): resolves the share token, renders the dashboard's tiles read-only through the normal
 * `VizPlugin` → `<inspecto-viz-render>` path, and fetches each tile's data via the token-fenced public
 * query. Per-tile failures degrade to an inline alert; only a bad token fails the whole page (the same
 * indistinguishable "not found" the backend answers for tampered/expired/unknown).
 */
@Component({
    selector: 'app-share-viewer',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoAlertComponent, InspectoSkeletonComponent, VizRenderComponent],
    templateUrl: './share-viewer.component.html',
})
export class ShareViewerComponent implements OnInit {
    /** The share token, bound from the `:token` route param (`withComponentInputBinding`). */
    readonly token = input.required<string>();

    private share = inject(ShareService);

    readonly state = signal<'loading' | 'ready' | 'error'>('loading');
    readonly errorMessage = signal('');
    readonly name = signal('');
    readonly expiresAt = signal('');
    readonly tiles = signal<TileVm[]>([]);

    ngOnInit(): void {
        this.share.resolve(this.token()).subscribe({
            next: (shared) => this.render(shared),
            error: (err) => {
                this.errorMessage.set(apiErrorMessage(err, 'This share link is invalid or has expired.'));
                this.state.set('error');
            },
        });
    }

    private render(shared: SharedDashboard): void {
        const content = shared.dashboard.content as { name?: string; tiles?: { widgetId: string; span?: number }[] };
        this.name.set(content.name || shared.dashboard.id);
        this.expiresAt.set(new Date(shared.expiresAt).toLocaleString());
        const widgets = new Map(shared.widgets.map((w) => [w.id, w.content as unknown as EmbedWidget]));

        const vms: TileVm[] = (content.tiles ?? []).map((tile) => {
            const widget = widgets.get(tile.widgetId);
            const span: 1 | 2 = tile.span === 2 ? 2 : 1;
            if (!widget) return { title: tile.widgetId, span, state: 'unsupported' as const,
                detail: 'This widget is no longer part of the shared dashboard.' };
            const title = widget.name || tile.widgetId;
            const plugin = getViz(widget.vizType);
            const body = embedQueryBody(widget);
            if (!plugin || !body) return { title, span, state: 'unsupported' as const,
                detail: 'This widget type cannot be embedded in a shared view.' };
            return { title, span, state: 'loading' as const, plugin };
        });
        this.tiles.set(vms);
        this.state.set('ready');

        (content.tiles ?? []).forEach((tile, i) => {
            const vm = vms[i];
            if (vm.state !== 'loading') return;
            const widget = widgets.get(tile.widgetId)!;
            this.share.query(this.token(), embedQueryBody(widget)!).subscribe({
                next: (r) => this.patchTile(i, { ...vm, state: 'ready',
                    props: vm.plugin!.transformProps(r.rows, widget.controls ?? {}) }),
                error: (err) => this.patchTile(i, { ...vm, state: 'error',
                    detail: apiErrorMessage(err, 'Could not load this widget’s data.') }),
            });
        });
    }

    private patchTile(index: number, vm: TileVm): void {
        this.tiles.update((all) => all.map((t, i) => (i === index ? vm : t)));
    }
}
