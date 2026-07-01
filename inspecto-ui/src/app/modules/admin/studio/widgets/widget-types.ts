import { ControlValues, VizRenderOptions } from 'app/inspecto/viz';

/**
 * Studio **Widget** model — a saved visualization = a dataset reference + a viz plugin type + the field→channel
 * mapping. Stored as a `widget` component (mock-served); its component `config` is the {@link WidgetConfig}.
 * The widget's "wiring" (in component-model terms) is the channel mapping. Mirrors `dataset-types.ts`.
 */
export interface WidgetConfig {
    datasetId: string;
    /** The VizPlugin type (`bar`/`line`/`kpi`/…). */
    vizType: string;
    /** The field→channel mapping the plugin compiles to a QuerySpec. */
    controls: ControlValues;
    /** Free-text tags for the library gallery's search/filter (e.g. `ops`, `billing`). */
    tags?: string[];
    /** Shown as the library card's subtitle. */
    description?: string;
    /** The advanced/cog options — all optional, the render host applies sane defaults when omitted. */
    options?: WidgetOptions;
}

/**
 * The advanced (cog-icon) config, distinct from the mandatory field mapping. A closed, curated set — no
 * free-form styling — so the widget stays simple to configure (colors resolve only from a named palette).
 * Extends the render-affecting {@link VizRenderOptions} with the two caption-only fields.
 */
export interface WidgetOptions extends VizRenderOptions {
    /** Caption override; defaults to the widget's name. */
    title?: string;
    subtitle?: string;
}

export interface Widget extends WidgetConfig {
    id: string;
    name: string;
}

/** Build a {@link Widget} from a name + dataset/viz/controls (mirrors `buildDataset`). */
export function buildWidget(
    name: string,
    datasetId: string,
    vizType: string,
    controls: ControlValues,
    extra?: Pick<WidgetConfig, 'tags' | 'description' | 'options'>,
): Widget {
    return {
        id: name,
        name,
        datasetId,
        vizType,
        controls,
        tags: extra?.tags,
        description: extra?.description,
        options: extra?.options,
    };
}
