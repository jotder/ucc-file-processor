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
