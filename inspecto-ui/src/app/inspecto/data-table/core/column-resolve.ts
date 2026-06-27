/**
 * Column-name resolution — framework-free. Explicit names win; otherwise derive from the first row's keys.
 * (The Angular component turns these into ag-Grid `ColDef`s; this stays type-only so `core/` has no UI deps.)
 */
export function fieldNames(rows: readonly Record<string, unknown>[], explicit?: readonly string[]): string[] {
    if (explicit?.length) return [...explicit];
    return rows.length ? Object.keys(rows[0]) : [];
}
