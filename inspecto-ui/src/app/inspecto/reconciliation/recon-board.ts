import { ICellRendererParams } from 'ag-grid-community';
import { TreeNode } from 'app/inspecto/tree-table';
import {
    CompareColumn, DEFAULT_BANDS, ReconBands, Reconciliation, ReconBreak, withinTolerance,
} from './reconciliation-types';

/**
 * Reconciliation Board wire types + the offline aggregate engine (DAT-7,
 * `docs/superpower/reconciliation-board-design.md`). The types mirror the backend `/recon/run` and
 * `/recon/breaks` contracts byte-for-byte; {@link aggregateRecon}/{@link reconBreakSets} are the
 * in-browser mirror of the server's `ReconService` used when the Studio domain is mock-served
 * (`environment.mockStudio` — the same seam as `DatasetResultService`). Framework-free and
 * unit-tested for parity with the backend's `ReconServiceTest`.
 */

/** Wire name of the implicit COUNT(*) measure (always compared on the Board). */
export const RECON_RECORDS = '__records';

/** One Board grain row — the two sides' aggregated measures for one key-column combination. */
export interface ReconGrainRow {
    key: Record<string, unknown>;
    a: Record<string, number | null>;
    b: Record<string, number | null>;
    inA: boolean;
    inB: boolean;
}

export interface ReconRunSummary {
    groups: number;
    matchedKeys: number;
    byType: { missing_left: number; missing_right: number; value_break: number };
}

/** The `/recon/run` payload. */
export interface ReconRunResult {
    keyColumns: string[];
    measures: string[];
    rows: ReconGrainRow[];
    totals: { a: Record<string, number | null>; b: Record<string, number | null> };
    summary: ReconRunSummary;
    statistics: { rowCount: number; elapsedMs: number; truncated: boolean };
}

/** One row of a `/recon/breaks` set (missing sets carry a single side). */
export interface ReconBreakRow {
    key: Record<string, unknown>;
    a?: Record<string, number | null>;
    b?: Record<string, number | null>;
}

export interface ReconBreakSet {
    rows: ReconBreakRow[];
    rowCount: number;
    truncated: boolean;
}

/** The `/recon/breaks` payload, keyed by break type. */
export type ReconBreakSets = Partial<Record<'missing_left' | 'missing_right' | 'value_break', ReconBreakSet>>;

// ── offline aggregate engine (mirror of the backend ReconService) ────────────────────

const KEY_SEP = '\u0000';

interface SideGroup {
    key: Record<string, unknown>;
    measures: Record<string, number | null>;
}

/** Group one side's rows at the key grain with per-measure sum/count + the implicit record count. */
function groupSide(rows: Record<string, unknown>[], keyColumns: string[], compare: CompareColumn[]):
        Map<string, SideGroup> {
    const groups = new Map<string, SideGroup>();
    for (const row of rows) {
        const id = keyColumns.map((k) => String(row[k] ?? '')).join(KEY_SEP);
        let g = groups.get(id);
        if (!g) {
            const key: Record<string, unknown> = {};
            for (const k of keyColumns) key[k] = row[k] ?? null;
            g = { key, measures: { [RECON_RECORDS]: 0 } };
            // COUNT(col) of an all-NULL group is 0 (backend parity); SUM stays NULL until a value lands.
            for (const c of compare) g.measures[c.column] = (c.agg ?? 'sum') === 'count' ? 0 : null;
            groups.set(id, g);
        }
        g.measures[RECON_RECORDS] = (g.measures[RECON_RECORDS] ?? 0) + 1;
        for (const c of compare) {
            const v = row[c.column];
            if (v === null || v === undefined) continue;
            if ((c.agg ?? 'sum') === 'count') {
                g.measures[c.column] = (g.measures[c.column] ?? 0) + 1;
            } else {
                const n = Number(v);
                if (!Number.isNaN(n)) g.measures[c.column] = (g.measures[c.column] ?? 0) + n;
            }
        }
    }
    return groups;
}

/** Aggregated-value agreement — {@link withinTolerance} over the rolled-up numbers (backend parity). */
function aggWithin(a: number | null, b: number | null, c: CompareColumn): boolean {
    if (a === null || b === null) return a === b;
    return withinTolerance(a, b, c);
}

/** Human-readable break key for a grain row (also the {@code breakId} identity component). */
export function breakKeyOf(key: Record<string, unknown>, keyColumns: string[]): string {
    return keyColumns.map((k) => String(key[k] ?? '')).join(' · ');
}

/**
 * Map `/recon/breaks` sets to the C9 record model {@link ReconBreak} (all `open`) so the locked
 * lifecycle — {@code mergeBreaks} auto-close + preserved manual resolutions — keeps working unchanged
 * over server-computed breaks. A value-break grain row expands to one break per compare column that is
 * actually outside its tolerance.
 */
export function breaksFromSets(
    recon: Pick<Reconciliation, 'keyColumns' | 'compareColumns'>,
    sets: ReconBreakSets,
): ReconBreak[] {
    const out: ReconBreak[] = [];
    for (const row of sets.missing_right?.rows ?? [])
        out.push({ key: breakKeyOf(row.key, recon.keyColumns), type: 'missing_right', status: 'open' });
    for (const row of sets.missing_left?.rows ?? [])
        out.push({ key: breakKeyOf(row.key, recon.keyColumns), type: 'missing_left', status: 'open' });
    for (const row of sets.value_break?.rows ?? []) {
        for (const c of recon.compareColumns) {
            const a = row.a?.[c.column] ?? null;
            const b = row.b?.[c.column] ?? null;
            if (aggWithin(a, b, c)) continue;
            out.push({
                key: breakKeyOf(row.key, recon.keyColumns),
                type: 'value_break',
                column: c.column,
                leftValue: a,
                rightValue: b,
                diff: a !== null && b !== null ? a - b : undefined,
                status: 'open',
            });
        }
    }
    return out;
}

/** The in-browser mirror of `POST /recon/run` over already-resolved side rows. */
export function aggregateRecon(
    recon: Pick<Reconciliation, 'keyColumns' | 'compareColumns'>,
    leftRows: Record<string, unknown>[],
    rightRows: Record<string, unknown>[],
): ReconRunResult {
    const t0 = Date.now();
    const { keyColumns, compareColumns } = recon;
    const a = groupSide(leftRows, keyColumns, compareColumns);
    const b = groupSide(rightRows, keyColumns, compareColumns);
    const measures = [...compareColumns.map((c) => c.column), RECON_RECORDS];

    const ids = [...new Set([...a.keys(), ...b.keys()])].sort();
    const rows: ReconGrainRow[] = [];
    const summary: ReconRunSummary = {
        groups: ids.length, matchedKeys: 0,
        byType: { missing_left: 0, missing_right: 0, value_break: 0 },
    };
    const emptySide = (): Record<string, number | null> => {
        const m: Record<string, number | null> = { [RECON_RECORDS]: null };
        for (const c of compareColumns) m[c.column] = null;
        return m;
    };
    for (const id of ids) {
        const ga = a.get(id);
        const gb = b.get(id);
        rows.push({
            key: (ga ?? gb)!.key,
            a: ga?.measures ?? emptySide(),
            b: gb?.measures ?? emptySide(),
            inA: !!ga,
            inB: !!gb,
        });
        if (ga && !gb) summary.byType.missing_right++;
        else if (gb && !ga) summary.byType.missing_left++;
        else if (ga && gb) {
            summary.matchedKeys++;
            for (const c of compareColumns)
                if (!aggWithin(ga.measures[c.column], gb.measures[c.column], c)) summary.byType.value_break++;
        }
    }

    const totals = { a: sideTotals(a, compareColumns), b: sideTotals(b, compareColumns) };
    return {
        keyColumns, measures, rows, totals, summary,
        statistics: { rowCount: rows.length, elapsedMs: Date.now() - t0, truncated: false },
    };
}

function sideTotals(groups: Map<string, SideGroup>, compare: CompareColumn[]): Record<string, number | null> {
    const totals: Record<string, number | null> = { [RECON_RECORDS]: 0 };
    for (const c of compare) totals[c.column] = null;
    for (const g of groups.values()) {
        totals[RECON_RECORDS] = (totals[RECON_RECORDS] ?? 0) + (g.measures[RECON_RECORDS] ?? 0);
        for (const c of compare) {
            const v = g.measures[c.column];
            if (v !== null && v !== undefined) totals[c.column] = (totals[c.column] ?? 0) + v;
        }
    }
    return totals;
}

/** The in-browser mirror of `POST /recon/breaks` (all three sets, optionally path-scoped / type-filtered). */
export function reconBreakSets(
    recon: Pick<Reconciliation, 'keyColumns' | 'compareColumns'>,
    leftRows: Record<string, unknown>[],
    rightRows: Record<string, unknown>[],
    path?: Record<string, string> | null,
    type?: 'missing_left' | 'missing_right' | 'value_break' | null,
): ReconBreakSets {
    const run = aggregateRecon(recon, leftRows, rightRows);
    const inPath = (key: Record<string, unknown>): boolean =>
        !path || Object.entries(path).every(([dim, v]) => String(key[dim] ?? '') === v);
    const set = (rows: ReconBreakRow[]): ReconBreakSet => ({ rows, rowCount: rows.length, truncated: false });

    const out: ReconBreakSets = {};
    if (!type || type === 'missing_right')
        out.missing_right = set(run.rows.filter((r) => r.inA && !r.inB && inPath(r.key)).map((r) => ({ key: r.key, a: r.a })));
    if (!type || type === 'missing_left')
        out.missing_left = set(run.rows.filter((r) => r.inB && !r.inA && inPath(r.key)).map((r) => ({ key: r.key, b: r.b })));
    if (!type || type === 'value_break')
        out.value_break = set(run.rows
            .filter((r) => r.inA && r.inB && inPath(r.key)
                && recon.compareColumns.some((c) => !aggWithin(r.a[c.column], r.b[c.column], c)))
            .map((r) => ({ key: r.key, a: r.a, b: r.b })));
    return out;
}

// ── Board tree + severity bands ──────────────────────────────────────────────────────

export type ReconBand = 'ok' | 'warn' | 'breach' | 'structural';

/**
 * Δ% of `b` vs the anchor `a`, from ROLLED-UP values (never averaged child Δ%s). `null` = no meaningful
 * percentage: a missing/NULL side, or a zero anchor with a non-zero other ("new" — structural severity).
 */
export function deltaPct(a: number | null | undefined, b: number | null | undefined): number | null {
    if (a === null || a === undefined || b === null || b === undefined) return null;
    if (a === 0) return b === 0 ? 0 : null;
    return ((b - a) / Math.abs(a)) * 100;
}

/** The severity band for one Δ% cell. */
export function bandFor(pct: number | null | undefined, bands: ReconBands = DEFAULT_BANDS): ReconBand {
    if (pct === null || pct === undefined) return 'structural';
    const abs = Math.abs(pct);
    if (abs > bands.breachPct) return 'breach';
    if (abs >= bands.warnPct) return 'warn';
    return 'ok';
}

/** Severity rank for sibling ordering (worst first). */
function severityRank(band: ReconBand, pct: number | null): number {
    const base = band === 'structural' ? 3 : band === 'breach' ? 2 : band === 'warn' ? 1 : 0;
    return base * 1_000_000 + Math.min(Math.abs(pct ?? 0), 999_999);
}

/** Encode a Board dimension path (`region=EU`, `product=data`) as a stable node id / query param. */
export function encodePath(path: Record<string, unknown>, keyColumns: string[]): string {
    return keyColumns
        .filter((k) => k in path)
        .map((k) => `${k}:${encodeURIComponent(String(path[k] ?? ''))}`)
        .join('|');
}

/** Decode {@link encodePath}'s form back to a dim → value map (unknown segments are skipped). */
export function decodePath(encoded: string | null | undefined): Record<string, string> | null {
    if (!encoded) return null;
    const out: Record<string, string> = {};
    for (const seg of encoded.split('|')) {
        const i = seg.indexOf(':');
        if (i > 0) out[seg.slice(0, i)] = decodeURIComponent(seg.slice(i + 1));
    }
    return Object.keys(out).length ? out : null;
}

/**
 * Fold grain rows into the Board's dimension tree (key-column order = hierarchy). Every node carries
 * per-measure `a_<m>` / `b_<m>` / `pct_<m>` values plus `__structural` ('a' | 'b' when the whole node is
 * one-sided) and `__path` (the encoded dimension path for drill). Parent measures are the SUM of their
 * children (missing side contributes 0 while present anywhere below — COALESCE semantics), and parent Δ%
 * is recomputed from those rolled-up sums. Siblings sort worst-severity-first.
 */
export function buildBoardTree(result: ReconRunResult, bands: ReconBands = DEFAULT_BANDS): TreeNode[] {
    return buildLevel(result.rows, result, bands, 0, {});
}

function buildLevel(
    rows: ReconGrainRow[],
    result: ReconRunResult,
    bands: ReconBands,
    depth: number,
    parentPath: Record<string, unknown>,
): TreeNode[] {
    const dim = result.keyColumns[depth];
    const byValue = new Map<string, ReconGrainRow[]>();
    for (const r of rows) {
        const v = String(r.key[dim] ?? '');
        byValue.set(v, [...(byValue.get(v) ?? []), r]);
    }
    const nodes: { node: TreeNode; rank: number }[] = [];
    for (const [value, group] of byValue) {
        const path = { ...parentPath, [dim]: group[0].key[dim] };
        const id = encodePath(path, result.keyColumns);
        const inA = group.some((r) => r.inA);
        const inB = group.some((r) => r.inB);
        const structural: 'a' | 'b' | null = inA && !inB ? 'a' : inB && !inA ? 'b' : null;
        const values: Record<string, unknown> = { __structural: structural, __path: id };
        let worst = 0;
        for (const m of result.measures) {
            const a = rollup(group, 'a', m, inA);
            const b = rollup(group, 'b', m, inB);
            const pct = structural ? null : deltaPct(a, b);
            values[`a_${m}`] = a;
            values[`b_${m}`] = b;
            values[`pct_${m}`] = pct;
            worst = Math.max(worst, severityRank(structural ? 'structural' : bandFor(pct, bands), pct));
        }
        const leaf = depth === result.keyColumns.length - 1;
        nodes.push({
            rank: worst,
            node: {
                id,
                label: value === '' ? '(blank)' : value,
                values,
                children: leaf ? undefined : buildLevel(group, result, bands, depth + 1, path),
            },
        });
    }
    nodes.sort((x, y) => y.rank - x.rank || x.node.label.localeCompare(y.node.label));
    return nodes.map((n) => n.node);
}

/** Sum a measure across grain rows for one side; `null` when the side is absent below this node. */
function rollup(group: ReconGrainRow[], side: 'a' | 'b', measure: string, present: boolean): number | null {
    if (!present) return null;
    let sum = 0;
    let any = false;
    for (const r of group) {
        const v = r[side][measure];
        if (v !== null && v !== undefined) {
            sum += v;
            any = true;
        }
    }
    return any ? sum : null;
}

/** Copy of `nodes` with `expanded: true` on every ancestor of a warn/breach/structural node. */
export function markBreachesExpanded(nodes: TreeNode[], bands: ReconBands = DEFAULT_BANDS): TreeNode[] {
    const mark = (n: TreeNode): { node: TreeNode; hot: boolean } => {
        const children = (n.children ?? []).map(mark);
        const selfHot = nodeBand(n, bands) !== 'ok';
        const hot = selfHot || children.some((c) => c.hot);
        return {
            hot,
            node: {
                ...n,
                expanded: children.some((c) => c.hot) || undefined,
                children: n.children ? children.map((c) => c.node) : undefined,
            },
        };
    };
    return nodes.map((n) => mark(n).node);
}

/** A node's worst band across its measures. */
export function nodeBand(n: TreeNode, bands: ReconBands = DEFAULT_BANDS): ReconBand {
    const v = n.values ?? {};
    if (v['__structural']) return 'structural';
    let worst: ReconBand = 'ok';
    for (const key of Object.keys(v)) {
        if (!key.startsWith('pct_')) continue;
        const band = bandFor(v[key] as number | null, bands);
        if (band === 'breach' || band === 'structural') return band === 'structural' ? 'structural' : 'breach';
        if (band === 'warn') worst = 'warn';
    }
    return worst;
}

// ── cell renderers (text tones only — the token guard forbids status-tinted fills) ───

const BAND_TONE: Record<ReconBand, string> = {
    ok: 'text-green-600 dark:text-green-400',
    warn: 'text-amber-600 dark:text-amber-400',
    breach: 'text-red-600 dark:text-red-400 font-medium',
    structural: 'text-red-600 dark:text-red-400',
};
const BAND_GLYPH: Record<ReconBand, string> = { ok: '✓', warn: '!', breach: '✕', structural: '⊘' };

/**
 * String cell-renderer for a Board Δ% column: severity glyph + signed percentage, text-tone only
 * (glyph + text carry the meaning — never color alone). A structural row renders "only in A/B";
 * a zero-anchor "new" value renders the breach glyph with "new".
 */
export function bandCell(bands: ReconBands = DEFAULT_BANDS): (p: ICellRendererParams) => string {
    return (p) => {
        const structural = (p.data as Record<string, unknown> | undefined)?.['__structural'] as string | null;
        if (structural) {
            const side = structural === 'a' ? 'A' : 'B';
            return `<span class="${BAND_TONE.structural}">${BAND_GLYPH.structural} only in ${side}</span>`;
        }
        const pct = p.value as number | null | undefined;
        if (pct === null || pct === undefined)
            return `<span class="${BAND_TONE.breach}">${BAND_GLYPH.breach} new</span>`;
        const band = bandFor(pct, bands);
        const text = `${pct > 0 ? '+' : ''}${pct.toFixed(1)}%`;
        return `<span class="${BAND_TONE[band]}">${BAND_GLYPH[band]} ${text}</span>`;
    };
}

/** Compact numeric formatter for the Board's measure value columns (`—` for a missing side). */
export function fmtMeasure(v: unknown): string {
    if (v === null || v === undefined) return '—';
    const n = Number(v);
    if (Number.isNaN(n)) return String(v);
    return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
