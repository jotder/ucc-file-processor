import { ComponentKind, ConfigFinding, Part, Ref, Wiring, getKind, registerKind, widgetRefs } from 'app/inspecto/component-model';
import { getViz, getVizComponentLoader, registerVizComponent } from 'app/inspecto/viz';
// Side-effect: ensure the built-in VizPlugins are registered (the widget kind's sub-types).
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { WidgetConfig } from './widget-types';

registerBuiltinViz();

// The view-bound plugins' render hosts, registered as ASYNC loaders so MapLibre/G6 stay out of every
// eager bundle that pulls this module in (explore, dashboards, the gallery). Guarded like registerBuiltinViz.
if (!getVizComponentLoader('geo-map-view')) {
    registerVizComponent('geo-map-view', () =>
        import('../geo-map/geo-view-widget.component').then((m) => m.GeoViewWidgetComponent),
    );
}
if (!getVizComponentLoader('link-analysis-view')) {
    registerVizComponent('link-analysis-view', () =>
        import('../link-analysis/link-view-widget.component').then((m) => m.LinkViewWidgetComponent),
    );
}
if (!getVizComponentLoader('reconciliation')) {
    registerVizComponent('reconciliation', () =>
        import('../../reconciliation/recon-view-widget.component').then((m) => m.ReconViewWidgetComponent),
    );
}

/**
 * The `widget` {@link ComponentKind} — the adoption plan's "VizPlugin = first ComponentKind slice" made
 * concrete. A widget references a dataset (its one part kind) and authors a **mapping** wiring (field→channel),
 * derived from its config. Authoring = the Studio explore workbench; exec = the viz runner (AlaSQL now).
 */
export const WIDGET_KIND: ComponentKind<WidgetConfig> = {
    id: 'widget',
    label: 'Widget',
    allowedPartKinds: ['dataset'],
    wiring: 'mapping',
    config: {
        validate: validateWidgetConfig,
        create: () => ({ datasetId: '', vizType: 'bar', controls: {} }),
    },
    deriveWiring: (_parts: Part[], config: WidgetConfig): Wiring => ({
        strategy: 'mapping',
        channels: channelMap(config.controls),
    }),
    deriveRefs: (config: WidgetConfig): Ref[] => widgetRefs(config as unknown as Record<string, unknown>),
    authoring: { editorKey: 'widget' },
    exec: { runnerKey: 'viz' },
};

/** Flatten the field-mapper state into a channel→fields map (the widget's wiring). */
function channelMap(controls: WidgetConfig['controls']): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [channel, vals] of Object.entries(controls)) {
        if (vals?.length) out[channel] = vals.map((v) => v.field).join(',');
    }
    return out;
}

/** Tiny hand-written validator (no schema engine): a widget needs a viz type, plus a dataset — or, for a
 *  view-bound viz type (`meta.viewKind`), a saved view instead. */
export function validateWidgetConfig(config: unknown): ConfigFinding[] {
    const c = (config ?? {}) as Partial<WidgetConfig>;
    const findings: ConfigFinding[] = [];
    const viewBound = !!getViz(c.vizType ?? '')?.meta.viewKind;
    if (viewBound) {
        if (!c.viewId) findings.push({ severity: 'error', path: 'viewId', message: 'Pick a saved view.' });
    } else if (!c.datasetId) {
        findings.push({ severity: 'error', path: 'datasetId', message: 'Pick a dataset.' });
    }
    if (!c.vizType) findings.push({ severity: 'error', path: 'vizType', message: 'Pick a visualization.' });
    return findings;
}

if (!getKind(WIDGET_KIND.id)) {
    registerKind(WIDGET_KIND);
}
