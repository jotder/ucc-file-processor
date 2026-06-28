/**
 * Pure, framework-agnostic display formatters shared across the app — the P4 consolidation of helpers that
 * were duplicated inline in several components. Templates use the thin pipes in `pipes.ts`; TS call sites import
 * these directly. No Angular imports (vitest-pure, like `query/`). Only formatters with ≥2 real call sites live
 * here (adoption-plan STOP).
 */

/** A date-time for grids / detail views — epoch millis or ISO string → locale string ('' for empty/falsy). */
export function fmtDateTime(value: unknown): string {
    if (!value) return '';
    const d = typeof value === 'number' ? new Date(value) : new Date(String(value));
    return isNaN(d.getTime()) ? String(value) : d.toLocaleString();
}

/** A whole-number count with thousands separators (rounds first). */
export function fmtInt(n: number): string {
    return Math.round(n).toLocaleString();
}

/** A human byte size — B / KB / MB / GB / TB, one decimal above 1 KB. */
export function fmtBytes(n: number): string {
    if (n < 1024) return `${Math.round(n)} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let v = n / 1024;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
    }
    return `${v.toFixed(1)} ${units[i]}`;
}

/** A ratio (0–1) as a percentage with one decimal, e.g. 0.0123 → "1.2%". */
export function fmtPercent(ratio: number): string {
    return (ratio * 100).toFixed(1) + '%';
}
