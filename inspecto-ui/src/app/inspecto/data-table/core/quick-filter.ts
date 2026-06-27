/**
 * Client-side quick filter — framework-free. A case-insensitive substring match across the stringified cells
 * of the given columns (or all keys). Kept pure so CSV export can reproduce exactly what the search box shows.
 */
export function quickFilterRows<T extends Record<string, unknown>>(
    rows: readonly T[],
    text: string,
    columns?: readonly string[],
): T[] {
    const q = (text ?? '').trim().toLowerCase();
    if (!q) return [...rows];
    return rows.filter((r) => {
        const cols = columns?.length ? columns : Object.keys(r);
        return cols.some((c) => String(r[c] ?? '').toLowerCase().includes(q));
    });
}
