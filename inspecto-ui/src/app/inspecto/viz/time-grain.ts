import { TimeGrain } from './viz-types';

/**
 * Offline time bucketing — rewrite a temporal column's values to their bucket key *before* the QuerySpec
 * runs, so AlaSQL's ordinary GROUP BY groups per bucket. The server-side equivalent is `DATE_TRUNC` when
 * QuerySpec compiles to DuckDB (P4); keeping the bucket keys ISO-date-shaped (`YYYY-MM-DD` / `YYYY-MM`)
 * makes the two paths comparable. Pure data, no Angular.
 */

/** Bucket one value: `day`/`week` → `YYYY-MM-DD` (week = its Monday), `month` → `YYYY-MM`.
 *  Unparseable values and `auto` pass through unchanged. */
export function bucketValue(value: unknown, grain: TimeGrain): unknown {
    if (grain === 'auto' || value == null) return value;
    const d = new Date(String(value));
    if (Number.isNaN(d.getTime())) return value;
    if (grain === 'month') return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
    if (grain === 'week') {
        const monday = new Date(d);
        monday.setDate(d.getDate() - ((d.getDay() + 6) % 7));
        return isoDate(monday);
    }
    return isoDate(d);
}

/** Rewrite `field` in every row to its bucket key. No-op (same array) for `auto`/missing grain. */
export function bucketRows(
    rows: Record<string, unknown>[],
    field: string,
    grain: TimeGrain | undefined,
): Record<string, unknown>[] {
    if (!grain || grain === 'auto') return rows;
    return rows.map((r) => ({ ...r, [field]: bucketValue(r[field], grain) }));
}

function isoDate(d: Date): string {
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function pad(n: number): string {
    return String(n).padStart(2, '0');
}
