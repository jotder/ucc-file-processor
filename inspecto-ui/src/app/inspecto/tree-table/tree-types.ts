import { ICellRendererParams } from 'ag-grid-community';

/**
 * A node in an aligned tree-table forest: a label for the hierarchy (tree) column plus a bag of
 * `values` for the aligned right-hand columns. Framework-free so the flatten/seed helpers stay unit-testable.
 */
export interface TreeNode {
    /** Unique across the whole forest — used as the ag-Grid row id. */
    id: string;
    /** Shown in the tree (first) column. */
    label: string;
    /** Aligned right-column values, keyed by column field. */
    values?: Record<string, unknown>;
    children?: TreeNode[];
    /** Optional leading heroicon (e.g. a data-type or status glyph) shown in the tree column. */
    icon?: string;
    /** Initial-state hint: expand this node on first render regardless of `groupDefaultExpanded`. */
    expanded?: boolean;
}

/** A flattened, grid-ready row. Tree metadata is carried on `__`-prefixed keys; `values` are spread flat. */
export interface FlatTreeRow {
    __id: string;
    __depth: number;
    __hasChildren: boolean;
    __expanded: boolean;
    __label: string;
    __icon?: string;
    [field: string]: unknown;
}

/**
 * Flatten `nodes` into the visible rows: a node's children are emitted only while the node's id is in
 * `expanded` (and, transitively, all its ancestors). Depth-first, preserving authoring order.
 */
export function flattenTree(nodes: TreeNode[], expanded: ReadonlySet<string>): FlatTreeRow[] {
    const out: FlatTreeRow[] = [];
    const walk = (list: TreeNode[], depth: number): void => {
        for (const n of list) {
            const hasChildren = !!(n.children && n.children.length);
            const isExpanded = hasChildren && expanded.has(n.id);
            out.push({
                __id: n.id,
                __depth: depth,
                __hasChildren: hasChildren,
                __expanded: isExpanded,
                __label: n.label,
                __icon: n.icon,
                ...(n.values ?? {}),
            });
            if (isExpanded) walk(n.children!, depth + 1);
        }
    };
    walk(nodes, 0);
    return out;
}

/**
 * The set of node ids to expand on load: every node shallower than `maxDepth`, plus any node marked
 * `expanded: true`. Pass {@link Number.MAX_SAFE_INTEGER} for "expand all".
 */
export function seedExpanded(nodes: TreeNode[], maxDepth: number): Set<string> {
    const set = new Set<string>();
    const walk = (list: TreeNode[], depth: number): void => {
        for (const n of list) {
            if (n.children && n.children.length) {
                if (depth < maxDepth || n.expanded) set.add(n.id);
                walk(n.children, depth + 1);
            }
        }
    };
    walk(nodes, 0);
    return set;
}

/** Collect every node id in the forest that has children (for "expand all"). */
export function allParentIds(nodes: TreeNode[]): Set<string> {
    return seedExpanded(nodes, Number.MAX_SAFE_INTEGER);
}

/**
 * A string cell-renderer for a variance / delta column: renders the signed number with a ▲/▼ direction
 * glyph and a text-tone emphasis (text-* tones are permitted by the design-system token guard; no
 * status-tinted fills). Blank/non-numeric values pass through as text.
 */
export function varianceCell(opts?: { format?: (n: number) => string }): (p: ICellRendererParams) => string {
    const fmt = opts?.format ?? ((n: number) => (n > 0 ? '+' : '') + n);
    return (p) => {
        const v = p.value;
        if (v === null || v === undefined || v === '') return '';
        const n = Number(v);
        if (Number.isNaN(n)) return String(v);
        const arrow = n > 0 ? '▲' : n < 0 ? '▼' : '·';
        const tone =
            n > 0 ? 'text-green-600 dark:text-green-400' : n < 0 ? 'text-red-600 dark:text-red-400' : 'opacity-60';
        return `<span class="${tone}">${arrow} ${fmt(n)}</span>`;
    };
}
