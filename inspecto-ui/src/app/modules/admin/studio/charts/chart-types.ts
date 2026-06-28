import { ControlValues } from 'app/inspecto/viz';

/**
 * Studio **Chart** model — a saved visualization = a dataset reference + a viz plugin type + the field→channel
 * mapping. Stored as a `chart` component (mock-served); its component `config` is the {@link ChartConfig}.
 * The chart's "wiring" (in component-model terms) is the channel mapping. Mirrors `dataset-types.ts`.
 */
export interface ChartConfig {
    datasetId: string;
    /** The VizPlugin type (`bar`/`line`/`kpi`/…). */
    vizType: string;
    /** The field→channel mapping the plugin compiles to a QuerySpec. */
    controls: ControlValues;
}

export interface Chart extends ChartConfig {
    id: string;
    name: string;
}

/** Build a {@link Chart} from a name + dataset/viz/controls (mirrors `buildDataset`). */
export function buildChart(name: string, datasetId: string, vizType: string, controls: ControlValues): Chart {
    return { id: name, name, datasetId, vizType, controls };
}
