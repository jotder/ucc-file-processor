import { emptyTree, MenuBinding, MenuNode, MenuTree } from './menu-types';

/** Id generator seam — tests inject a deterministic counter. */
export type IdGen = () => string;

const defaultIdGen: IdGen = () =>
    typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : 'm_' + Math.random().toString(36).slice(2, 10);

// ── pure tree helpers (immutable: each rebuilds only the affected path) ─────────────────────────
function insertChild(nodes: MenuNode[], parentId: string | null, node: MenuNode): MenuNode[] {
    if (parentId === null) return [...nodes, node];
    return nodes.map((n) => {
        if (n.id === parentId) return { ...n, children: [...(n.children ?? []), node] };
        if (n.children) return { ...n, children: insertChild(n.children, parentId, node) };
        return n;
    });
}

function patchNode(nodes: MenuNode[], id: string, patch: (n: MenuNode) => MenuNode): MenuNode[] {
    return nodes.map((n) => {
        if (n.id === id) return patch(n);
        if (n.children) return { ...n, children: patchNode(n.children, id, patch) };
        return n;
    });
}

function removeNode(nodes: MenuNode[], id: string): MenuNode[] {
    return nodes
        .filter((n) => n.id !== id)
        .map((n) => (n.children ? { ...n, children: removeNode(n.children, id) } : n));
}

function findNode(nodes: MenuNode[], id: string): MenuNode | undefined {
    for (const n of nodes) {
        if (n.id === id) return n;
        if (n.children) {
            const hit = findNode(n.children, id);
            if (hit) return hit;
        }
    }
    return undefined;
}

/** Remove the subtree `id`, returning the remaining forest and the detached node (if found). */
function detachNode(nodes: MenuNode[], id: string): { remaining: MenuNode[]; detached?: MenuNode } {
    let detached: MenuNode | undefined;
    const remaining: MenuNode[] = [];
    for (const n of nodes) {
        if (n.id === id) {
            detached = n;
            continue;
        }
        if (n.children) {
            const r = detachNode(n.children, id);
            if (r.detached) detached = r.detached;
            remaining.push({ ...n, children: r.remaining });
        } else {
            remaining.push(n);
        }
    }
    return { remaining, detached };
}

function siblingsOf(nodes: MenuNode[], parentId: string | null): MenuNode[] {
    return parentId === null ? nodes : (findNode(nodes, parentId)?.children ?? []);
}

/**
 * Framework-free CRUD over a {@link MenuTree}. Immutable internally, stateful wrapper for ergonomics +
 * tests. Node-creating ops return the new node id. The Angular {@link MenuService} runs these against
 * the active Space's tree and persists the {@link snapshot}.
 */
export class MenuStore {
    private tree: MenuTree;

    constructor(
        tree?: MenuTree,
        private readonly idGen: IdGen = defaultIdGen,
    ) {
        this.tree = tree ?? emptyTree('default');
    }

    snapshot(): MenuTree {
        return this.tree;
    }
    nodes(): MenuNode[] {
        return this.tree.nodes;
    }
    find(id: string): MenuNode | undefined {
        return findNode(this.tree.nodes, id);
    }

    /** True if a sibling under `parentId` already uses `title` (case-insensitive), excluding `exceptId`. */
    hasSiblingTitle(parentId: string | null, title: string, exceptId?: string): boolean {
        const t = title.trim().toLowerCase();
        return siblingsOf(this.tree.nodes, parentId).some(
            (n) => n.id !== exceptId && n.title.trim().toLowerCase() === t,
        );
    }

    /** Add a top-level menu (group). Returns its id. */
    addMenu(title: string, icon?: string): string {
        return this.addChild(null, { title, icon, children: [] });
    }
    /** Add a sub-menu (group) under an existing group. Returns its id. */
    addSubMenu(parentId: string, title: string, icon?: string): string {
        return this.addChild(parentId, { title, icon, children: [] });
    }
    /** Add a leaf bound to a library component under `parentId` (or top-level when null). Returns its id. */
    attach(parentId: string | null, title: string, binding: MenuBinding, icon?: string): string {
        return this.addChild(parentId, { title, icon, binding });
    }

    /**
     * Populate a starter example — one menu with a sub-menu and a placed report — so a new Space has a
     * copy-me template instead of a blank slate (menu-builder-plan O3). Built from the same ops the
     * builder uses (normal id generation); the caller decides when to run it (opt-in, empty tree only).
     * Returns the top-level menu's id.
     */
    seedExample(): string {
        const revenue = this.addMenu('Revenue', 'heroicons_outline:banknotes');
        const overview = this.addSubMenu(revenue, 'Overview', 'heroicons_outline:chart-bar');
        this.attach(overview, 'Revenue dashboard', { kind: 'dashboard', componentId: 'revenue_overview' },
            'heroicons_outline:presentation-chart-line');
        return revenue;
    }

    private addChild(parentId: string | null, partial: Omit<MenuNode, 'id'>): string {
        const id = this.idGen();
        this.tree = { ...this.tree, nodes: insertChild(this.tree.nodes, parentId, { id, ...partial }) };
        return id;
    }

    rename(id: string, title: string): this {
        this.tree = { ...this.tree, nodes: patchNode(this.tree.nodes, id, (n) => ({ ...n, title })) };
        return this;
    }
    setIcon(id: string, icon: string | undefined): this {
        this.tree = { ...this.tree, nodes: patchNode(this.tree.nodes, id, (n) => ({ ...n, icon })) };
        return this;
    }
    remove(id: string): this {
        this.tree = { ...this.tree, nodes: removeNode(this.tree.nodes, id) };
        return this;
    }

    /** Reorder the children of `parentId` (null = top level) to match `orderedIds`; ids not named keep
     *  their relative order, appended after. */
    reorder(parentId: string | null, orderedIds: string[]): this {
        const sibs = siblingsOf(this.tree.nodes, parentId);
        const byId = new Map(sibs.map((n) => [n.id, n]));
        const next = orderedIds.map((id) => byId.get(id)).filter((n): n is MenuNode => !!n);
        for (const n of sibs) if (!orderedIds.includes(n.id)) next.push(n);
        this.tree =
            parentId === null
                ? { ...this.tree, nodes: next }
                : { ...this.tree, nodes: patchNode(this.tree.nodes, parentId, (n) => ({ ...n, children: next })) };
        return this;
    }

    /** Move a subtree under `newParentId` (null = top level) at `index` (default: end). No-op on a
     *  cycle (moving a node into its own descendant) or an unknown id. */
    move(id: string, newParentId: string | null, index?: number): this {
        if (id === newParentId) return this;
        const { remaining, detached } = detachNode(this.tree.nodes, id);
        if (!detached) return this;
        if (newParentId !== null && findNode([detached], newParentId)) return this; // no cycles
        const target = siblingsOf(remaining, newParentId);
        const at = index == null ? target.length : Math.max(0, Math.min(index, target.length));
        const nextSibs = [...target.slice(0, at), detached, ...target.slice(at)];
        this.tree = {
            ...this.tree,
            nodes:
                newParentId === null
                    ? nextSibs
                    : patchNode(remaining, newParentId, (n) => ({ ...n, children: nextSibs })),
        };
        return this;
    }
}
