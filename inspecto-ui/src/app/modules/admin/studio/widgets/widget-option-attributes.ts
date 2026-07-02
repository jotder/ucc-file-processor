import { AttributeSpec } from 'app/inspecto/component-model';
import { CHART_PALETTES } from 'app/inspecto/theme/chart-tokens';

/**
 * The widget **advanced-options** attribute set (the cog dialog) — every knob is always visible but
 * **none is required** (`tier: 'required', required: false`), the case that motivated decoupling
 * validation from the visibility tier. A closed, curated set (no free-form styling); colors resolve only
 * from a named palette. Flat keys here; {@link WidgetOptionsDialog} re-nests them into `axis`/`legend`
 * on save. Mirrors `job-attributes.ts` (the schema-form pilot).
 */
export const WIDGET_OPTION_ATTRIBUTES: AttributeSpec[] = [
    { key: 'title', label: 'Title', type: 'string', tier: 'required', required: false, placeholder: "Defaults to the widget's name" },
    { key: 'subtitle', label: 'Subtitle', type: 'string', tier: 'required', required: false },
    { key: 'xTitle', label: 'X axis title', type: 'string', tier: 'required', required: false },
    { key: 'yTitle', label: 'Y axis title', type: 'string', tier: 'required', required: false },
    { key: 'legendShow', label: 'Show legend', type: 'boolean', tier: 'required', required: false, default: true },
    {
        key: 'legendPosition', label: 'Legend position', type: 'select', tier: 'required', required: false, default: 'top',
        options: [
            { value: 'top', label: 'top' },
            { value: 'right', label: 'right' },
            { value: 'bottom', label: 'bottom' },
            { value: 'left', label: 'left' },
        ],
    },
    {
        key: 'palette', label: 'Color palette', type: 'select', tier: 'required', required: false,
        default: Object.keys(CHART_PALETTES)[0],
        options: Object.keys(CHART_PALETTES).map((p) => ({ value: p, label: p })),
    },
    {
        key: 'sort', label: 'Sort', type: 'select', tier: 'required', required: false, default: '',
        options: [
            { value: '', label: 'Unsorted' },
            { value: 'asc', label: 'Ascending' },
            { value: 'desc', label: 'Descending' },
        ],
    },
    { key: 'limit', label: 'Limit (top N)', type: 'number', tier: 'required', required: false, min: 1 },
    { key: 'stacked', label: 'Stack series', type: 'boolean', tier: 'required', required: false, default: false },
];
