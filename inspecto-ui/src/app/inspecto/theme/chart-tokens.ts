import { NodeKind } from 'app/inspecto/api';

/**
 * Concrete colour tokens for the canvas renderers (Chart.js, AntV G6). Canvas APIs
 * can't resolve CSS custom properties, so the Tailwind values used elsewhere in the
 * app are pinned here — this is the only place chart/graph colours may be hardcoded.
 */

/** Tailwind slate ramp (matches the app's neutral palette). */
const SLATE = {
    200: '#cbd5e1',
    400: '#94a3b8',
    500: '#64748b',
    700: '#334155',
    800: '#1e293b',
} as const;

/** Semantic series colours for dashboard charts (indigo-600 / green-500 / red-500). */
export const CHART_SERIES = {
    primary: '#4f46e5',
    success: '#22c55e',
    error: '#ef4444',
} as const;

/** Amber for "needs attention" canvas cues (Tailwind amber-500). */
const WARN = '#f59e0b';

/**
 * Categorical series ramp for Studio charts (multi-series line/bar/area, pie slices). Canvas can't read CSS
 * vars, so — like the other tokens here — these are the sanctioned hardcoded series colours. Reuses the
 * catalog kind accents so the platform's chart palette stays consistent.
 */
export const CHART_CATEGORICAL: readonly string[] = [
    '#5B8FF9', '#61DDAA', '#F6BD16', '#7262FD', '#F6903D', '#78D3F8', '#EF4444', '#65789B', '#22C55E', '#A855F7',
];

/** A muted single-hue ramp (indigo) — the curated alternative to {@link CHART_CATEGORICAL} for widgets that
 *  want a calmer look (e.g. a single dominant series). */
const CHART_MONOCHROME: readonly string[] = [
    '#4f46e5', '#6366f1', '#818cf8', '#a5b4fc', '#c7d2fe', '#e0e7ff',
];

/** Named, curated widget color palettes (`WidgetOptions.palette`) — never a free-form color list, so widgets
 *  stay visually consistent. Add a new entry here to offer another curated option. */
export const CHART_PALETTES: Readonly<Record<string, readonly string[]>> = {
    categorical: CHART_CATEGORICAL,
    monochrome: CHART_MONOCHROME,
};

/** The gauge widget's unfilled "remaining" track (Tailwind gray-400) — a neutral shade that reads on either
 *  scheme, since `VizRenderComponent` (unlike `InspectoChartComponent`) doesn't thread the dark/light flag. */
export const GAUGE_TRACK = '#9ca3af';

/**
 * Outline stroke for a flow-editor node's status cue, or {@code null} to keep the category colour
 * ({@code configured}). Status is also conveyed by a label glyph + the inspector chip (never colour alone).
 */
export function nodeStatusStroke(status: string): string | null {
    switch (status) {
        case 'tested':       return CHART_SERIES.success;
        case 'rejects':      return WARN;
        case 'unconfigured': return WARN;
        case 'dangling':     return CHART_SERIES.error;
        default:             return null; // 'configured' → keep the category colour
    }
}

/** Categorical accent per catalog node kind — drives the legend and node outlines. */
export const NODE_KIND_COLORS: Record<NodeKind, string> = {
    SOURCE: '#5B8FF9',
    SCHEMA: '#61DDAA',
    TABLE: '#65789B',
    COLUMN: '#F6BD16',
    KPI: '#7262FD',
    REPORT: '#78D3F8',
    ENRICHMENT: '#F6903D',
};

export const NODE_KIND_FALLBACK = '#9AA0A6';

/** Curated colour palette offered in the configurable-icon picker (the kind accents + a few extras). */
export const ICON_COLOR_SWATCHES: readonly string[] = [
    '#5B8FF9', '#61DDAA', '#65789B', '#F6BD16', '#7262FD', '#78D3F8', '#F6903D',
    '#EF4444', '#22C55E', '#A855F7', '#0EA5E9', '#94A3B8',
];

/** Scheme-dependent colours shared by the chart and graph hosts. */
export interface CanvasTheme {
    /** Label / tick / legend text. */
    fg: string;
    /** Node fill behind the kind-coloured outline. */
    surface: string;
    /** Graph edge stroke. */
    edge: string;
    /** Chart gridline colour (translucent). */
    grid: string;
}

export function canvasTheme(dark: boolean): CanvasTheme {
    return dark
        ? { fg: SLATE[200], surface: SLATE[800], edge: SLATE[500], grid: 'rgba(148,163,184,0.15)' }
        : { fg: SLATE[700], surface: '#ffffff', edge: SLATE[400], grid: 'rgba(100,116,139,0.15)' };
}
