import { ComponentKind, ConfigFinding, Part, Wiring, getKind, registerKind } from 'app/inspecto/component-model';
// Side-effect: ensure the built-in VizPlugins are registered (the chart kind's sub-types).
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { ChartConfig } from './chart-types';

registerBuiltinViz();

/**
 * The `chart` {@link ComponentKind} — the adoption plan's "VizPlugin = first ComponentKind slice" made
 * concrete. A chart references a dataset (its one part kind) and authors a **mapping** wiring (field→channel),
 * derived from its config. Authoring = the Studio explore workbench; exec = the viz runner (AlaSQL now).
 */
export const CHART_KIND: ComponentKind<ChartConfig> = {
    id: 'chart',
    label: 'Chart',
    allowedPartKinds: ['dataset'],
    wiring: 'mapping',
    config: {
        validate: validateChartConfig,
        create: () => ({ datasetId: '', vizType: 'bar', controls: {} }),
    },
    deriveWiring: (_parts: Part[], config: ChartConfig): Wiring => ({
        strategy: 'mapping',
        channels: channelMap(config.controls),
    }),
    authoring: { editorKey: 'chart' },
    exec: { runnerKey: 'viz' },
};

/** Flatten the field-mapper state into a channel→fields map (the chart's wiring). */
function channelMap(controls: ChartConfig['controls']): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [channel, vals] of Object.entries(controls)) {
        if (vals?.length) out[channel] = vals.map((v) => v.field).join(',');
    }
    return out;
}

/** Tiny hand-written validator (no schema engine): a chart needs a dataset + a viz type. */
export function validateChartConfig(config: unknown): ConfigFinding[] {
    const c = (config ?? {}) as Partial<ChartConfig>;
    const findings: ConfigFinding[] = [];
    if (!c.datasetId) findings.push({ severity: 'error', path: 'datasetId', message: 'Pick a dataset.' });
    if (!c.vizType) findings.push({ severity: 'error', path: 'vizType', message: 'Pick a visualization.' });
    return findings;
}

if (!getKind(CHART_KIND.id)) {
    registerKind(CHART_KIND);
}
