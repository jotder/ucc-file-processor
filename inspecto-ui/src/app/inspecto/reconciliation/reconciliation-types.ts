/**
 * Reconciliation — C9 (Wave 3). Pure, framework-free: define a **Dataset-vs-Dataset** match (key
 * columns + per-column numeric tolerances) and compute the **breaks** between the two sides. Stored as a
 * `reconciliation` component (mock-served today), mirroring `dataset-types.ts`/`requirement-types.ts`'s
 * "just a component" shape.
 *
 * Semantics locked with the product owner 2026-07-03: match rows by `keyColumns`; for each
 * `compareColumns` entry apply an exact / absolute / percent tolerance before flagging a value break; a
 * break **auto-closes** when its key re-matches within tolerance on a later run (see {@link mergeBreaks}),
 * while manual resolutions are preserved across runs.
 */

export type ToleranceType = 'exact' | 'absolute' | 'percent';

/** How a compare column aggregates on the Board (record-grain break truth stays the tolerance). */
export type MeasureAgg = 'sum' | 'count';

/** One column compared between the two sides, with the tolerance under which a difference is NOT a break. */
export interface CompareColumn {
    column: string;
    toleranceType: ToleranceType;
    /** Ignored for `exact`. For `absolute`: max |left-right|. For `percent`: max |left-right| / |left| * 100. */
    tolerance: number;
    /** Board rollup aggregation (default `sum`). */
    agg?: MeasureAgg;
}

/** Board display severity for |Δ%| vs the anchor — independent of a column's record-level tolerance. */
export interface ReconBands {
    warnPct: number;
    breachPct: number;
}

/** The locked defaults: < 1 % ok · 1–2 % warn · > 2 % breach. */
export const DEFAULT_BANDS: ReconBands = { warnPct: 1, breachPct: 2 };

export type BreakType = 'missing_left' | 'missing_right' | 'value_break';
export type BreakStatus = 'open' | 'resolved' | 'auto_closed';

/** One reconciliation discrepancy for a single key (and, for value breaks, a single compare column). */
export interface ReconBreak {
    key: string;
    type: BreakType;
    /** The compare column that broke (value breaks only). */
    column?: string;
    leftValue?: unknown;
    rightValue?: unknown;
    /** Signed numeric difference left-right (value breaks on numeric columns only). */
    diff?: number;
    status: BreakStatus;
    /** Manual-resolution note (preserved across re-runs). */
    note?: string;
}

/** The persisted body of a `reconciliation` component (everything except id/name). */
export interface ReconciliationConfig {
    leftDataset: string;
    rightDataset: string;
    /** Optional third dataset — turns the recon 3-way (anchor = leftDataset, design §6). */
    thirdDataset?: string;
    keyColumns: string[];
    compareColumns: CompareColumn[];
    /** Board severity bands (defaults to {@link DEFAULT_BANDS} when absent). */
    bands?: ReconBands;
    /** Last run's breaks, kept for the lifecycle merge (auto-close / preserved manual resolutions). */
    breaks: ReconBreak[];
    lastRunAt?: string | null;
}

export interface Reconciliation extends ReconciliationConfig {
    id: string;
    name: string;
}

export interface ReconSummary {
    leftRows: number;
    rightRows: number;
    matchedKeys: number;
    open: number;
    resolved: number;
    autoClosed: number;
    byType: Record<BreakType, number>;
}

const KEY_SEP = '';

/** Composite key for a row (key column values joined). */
function keyOf(row: Record<string, unknown>, keyColumns: string[]): string {
    return keyColumns.map((k) => String(row[k] ?? '')).join(KEY_SEP);
}

/** Stable identity for a break within a run — key + type + column — used by {@link mergeBreaks}. */
export function breakId(b: ReconBreak): string {
    return `${b.type}${KEY_SEP}${b.key}${KEY_SEP}${b.column ?? ''}`;
}

/** True when `left` and `right` agree within the column's tolerance (non-numeric ⇒ exact string compare). */
export function withinTolerance(left: unknown, right: unknown, c: CompareColumn): boolean {
    if (c.toleranceType === 'exact') return String(left ?? '') === String(right ?? '');
    const a = Number(left);
    const b = Number(right);
    if (Number.isNaN(a) || Number.isNaN(b)) return String(left ?? '') === String(right ?? '');
    const delta = Math.abs(a - b);
    if (c.toleranceType === 'absolute') return delta <= c.tolerance;
    // percent — relative to |left|; with left 0 there is no meaningful percentage, so require exact equality.
    if (a === 0) return delta === 0;
    return (delta / Math.abs(a)) * 100 <= c.tolerance;
}

/**
 * Compute the fresh break set for a run. Every returned break is `open` — lifecycle (auto-close, preserved
 * manual resolutions) is applied separately by {@link mergeBreaks} against the previous run.
 */
export function runReconciliation(
    config: Pick<ReconciliationConfig, 'keyColumns' | 'compareColumns'>,
    leftRows: Record<string, unknown>[],
    rightRows: Record<string, unknown>[],
): ReconBreak[] {
    const { keyColumns, compareColumns } = config;
    const leftByKey = new Map(leftRows.map((r) => [keyOf(r, keyColumns), r]));
    const rightByKey = new Map(rightRows.map((r) => [keyOf(r, keyColumns), r]));
    const breaks: ReconBreak[] = [];

    for (const [key, lrow] of leftByKey) {
        const rrow = rightByKey.get(key);
        if (!rrow) {
            breaks.push({ key, type: 'missing_right', status: 'open' });
            continue;
        }
        for (const c of compareColumns) {
            if (withinTolerance(lrow[c.column], rrow[c.column], c)) continue;
            const a = Number(lrow[c.column]);
            const b = Number(rrow[c.column]);
            breaks.push({
                key,
                type: 'value_break',
                column: c.column,
                leftValue: lrow[c.column],
                rightValue: rrow[c.column],
                diff: Number.isNaN(a) || Number.isNaN(b) ? undefined : a - b,
                status: 'open',
            });
        }
    }
    for (const [key] of rightByKey) {
        if (!leftByKey.has(key)) breaks.push({ key, type: 'missing_left', status: 'open' });
    }
    return breaks;
}

/**
 * Merge a fresh run's breaks with the previous run's, per the locked lifecycle:
 * - a fresh break whose identity was **manually resolved** last run stays `resolved` (with its note);
 * - a previous open/resolved break that is **no longer present** (the key now matches within tolerance)
 *   becomes `auto_closed` for this run's report;
 * - previously `auto_closed` breaks that are still gone are dropped (bounded history).
 */
export function mergeBreaks(previous: ReconBreak[], fresh: ReconBreak[]): ReconBreak[] {
    const prevById = new Map(previous.map((b) => [breakId(b), b]));
    const freshIds = new Set(fresh.map(breakId));

    const carried = fresh.map((b) => {
        const p = prevById.get(breakId(b));
        return p && p.status === 'resolved' ? { ...b, status: 'resolved' as const, note: p.note } : b;
    });
    const autoClosed = previous
        .filter((p) => (p.status === 'open' || p.status === 'resolved') && !freshIds.has(breakId(p)))
        .map((p) => ({ ...p, status: 'auto_closed' as const }));
    return [...carried, ...autoClosed];
}

/** Manually resolve (or re-open) a break by identity, preserving everything else. */
export function resolveBreak(breaks: ReconBreak[], target: ReconBreak, resolved: boolean, note?: string): ReconBreak[] {
    const id = breakId(target);
    return breaks.map((b) =>
        breakId(b) === id ? { ...b, status: resolved ? 'resolved' : 'open', note: note?.trim() || undefined } : b,
    );
}

/** Roll a break set up into the report cards. */
export function summarize(breaks: ReconBreak[], leftRows: number, rightRows: number, matchedKeys: number): ReconSummary {
    const byType: Record<BreakType, number> = { missing_left: 0, missing_right: 0, value_break: 0 };
    let open = 0, resolved = 0, autoClosed = 0;
    for (const b of breaks) {
        if (b.status === 'auto_closed') autoClosed++;
        else {
            byType[b.type]++;
            if (b.status === 'resolved') resolved++;
            else open++;
        }
    }
    return { leftRows, rightRows, matchedKeys, open, resolved, autoClosed, byType };
}

/** Count keys present on both sides (for the summary's matched count). */
export function matchedKeyCount(
    keyColumns: string[],
    leftRows: Record<string, unknown>[],
    rightRows: Record<string, unknown>[],
): number {
    const right = new Set(rightRows.map((r) => keyOf(r, keyColumns)));
    let n = 0;
    for (const l of leftRows) if (right.has(keyOf(l, keyColumns))) n++;
    return n;
}

/** Build a fresh reconciliation definition (id = slug + suffix; nothing references it by id yet). */
export function buildReconciliation(
    name: string,
    leftDataset: string,
    rightDataset: string,
    keyColumns: string[],
    compareColumns: CompareColumn[],
): Reconciliation {
    const slug = name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'reconciliation';
    const suffix = Math.random().toString(36).slice(2, 6);
    return { id: `${slug}_${suffix}`, name: name.trim(), leftDataset, rightDataset, keyColumns, compareColumns, breaks: [], lastRunAt: null };
}
