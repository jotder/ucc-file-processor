/**
 * Menu Builder model — a user-curated navigation tree, shared per Space. Framework-free (no Angular) so
 * the tree logic is unit-testable in isolation; the Angular {@link MenuService} holds it in a signal and
 * the nav layer converts `MenuNode` → `GammaNavigationItem`. See docs/superpower/menu-builder-plan.md.
 *
 * A node is either a **group** (a menu / sub-menu — carries `children`) or a **leaf** (a Menu item bound
 * to a library Component via `binding`). Sibling order is array position (no separate order field).
 */

/** The library artifact kinds a Menu item can open (GLOSSARY: Widget / Dashboard / saved View). */
export type PlaceableKind = 'dashboard' | 'widget' | 'link-analysis-view' | 'geo-map-view';

/** What a leaf opens: a reference to a Component of the given kind. */
export interface MenuBinding {
    kind: PlaceableKind;
    componentId: string;
}

export interface MenuNode {
    id: string;
    title: string;
    /** gamma svg icon name, e.g. 'heroicons_outline:chart-bar'. */
    icon?: string;
    /** Present on a group node (menu / sub-menu). */
    children?: MenuNode[];
    /** Present on a leaf node (opens an artifact). Mutually exclusive with `children`. */
    binding?: MenuBinding;
}

/** The whole per-Space tree. `version` guards the persisted schema for future migrations. */
export interface MenuTree {
    space: string;
    version: 1;
    nodes: MenuNode[];
}

export const MENU_TREE_VERSION = 1 as const;

export function emptyTree(space: string): MenuTree {
    return { space, version: MENU_TREE_VERSION, nodes: [] };
}

/** A leaf opens an artifact; a group holds children. */
export function isLeaf(node: MenuNode): boolean {
    return node.binding != null;
}
