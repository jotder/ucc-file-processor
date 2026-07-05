import { QuerySpec, VizPlugin, VizProps } from '../viz-types';

/**
 * The **view-bound** plugins (Phase 4 of docs/superpower/geo-map-analysis-plan.md): a widget of these types
 * renders a saved investigation view — a `geo-map-view` / `link-analysis-view` Component — through the shared
 * studio hosts, instead of compiling a dataset + channel mapping. The query pipeline is inert (`controls` is
 * empty and callers skip it via `meta.viewKind`); the render host resolves `componentKey` to a lazily-loaded
 * read-only wrapper (see `viz-components.ts`).
 */

/** Inert spec — a view-bound plugin's data comes from its saved view's own GeoSource/GraphSource query. */
const NO_QUERY = (): QuerySpec => ({ datasetId: '', sourceName: '', groupBy: [], measures: [] });
const NO_PROPS = (): VizProps => ({ labels: [], series: [] });

export const GEO_MAP_PLUGIN: VizPlugin = {
    meta: { type: 'geo-map', label: 'Geo map', icon: 'heroicons_outline:map', fit: {}, viewKind: 'geo-map-view' },
    controls: [],
    buildQuery: NO_QUERY,
    transformProps: NO_PROPS,
    render: { kind: 'component', componentKey: 'geo-map-view' },
};

export const LINK_ANALYSIS_PLUGIN: VizPlugin = {
    meta: { type: 'link-analysis', label: 'Link analysis', icon: 'heroicons_outline:share', fit: {}, viewKind: 'link-analysis-view' },
    controls: [],
    buildQuery: NO_QUERY,
    transformProps: NO_PROPS,
    render: { kind: 'component', componentKey: 'link-analysis-view' },
};
