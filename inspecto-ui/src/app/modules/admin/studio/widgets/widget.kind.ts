import { ComponentKind, ConfigFinding, Part, Wiring, getKind, registerKind } from 'app/inspecto/component-model';
// Side-effect: ensure the built-in VizPlugins are registered (the widget kind's sub-types).
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { WidgetConfig } from './widget-types';

registerBuiltinViz();

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

/** Tiny hand-written validator (no schema engine): a widget needs a dataset + a viz type. */
export function validateWidgetConfig(config: unknown): ConfigFinding[] {
    const c = (config ?? {}) as Partial<WidgetConfig>;
    const findings: ConfigFinding[] = [];
    if (!c.datasetId) findings.push({ severity: 'error', path: 'datasetId', message: 'Pick a dataset.' });
    if (!c.vizType) findings.push({ severity: 'error', path: 'vizType', message: 'Pick a visualization.' });
    return findings;
}

if (!getKind(WIDGET_KIND.id)) {
    registerKind(WIDGET_KIND);
}
