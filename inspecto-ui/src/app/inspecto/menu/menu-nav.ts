import { GammaNavigationItem } from '@gamma/components/navigation';
import { MenuNode } from './menu-types';

/** Route prefix for the dynamic Menu-item host (M3): a leaf links to `/w/<nodeId>`. */
export const MENU_ITEM_ROUTE_PREFIX = '/w';

/**
 * Convert a user Menu tree into gamma sidebar items — a **group** becomes a `collapsable`, a **leaf**
 * becomes a `basic` item linking to the dynamic host route. Node ids are namespaced (`menu-…`) so they
 * never collide with the static platform-nav ids. The navigation mock merges the result as top-level
 * siblings of the Platform group (plan D3: "same sidebar, new groups").
 */
export function menuTreeToNav(nodes: MenuNode[]): GammaNavigationItem[] {
    return nodes.map(toNavItem);
}

/** Sidebar id of the virtual, personal "Favorites" group (plan §4.2). */
export const MENU_FAVORITES_NAV_ID = 'menu-favorites';

/**
 * Build the virtual **Favorites** group from a personal set of favorited leaf ids, resolved against the
 * current tree. Only leaves that still exist survive (a favorite whose item was renamed away or deleted
 * silently drops out); the favorite order is preserved. The resolved items get their own `fav-…` ids
 * (distinct from the `menu-…` ids the same leaves carry under their real group) so the shortcut never
 * collides with its origin in the sidebar. Returns `null` when nothing resolves, so the caller can omit
 * the group entirely. Only leaves are favouritable — a group has no artifact to open.
 */
export function favoritesNavGroup(nodes: MenuNode[], favoriteIds: string[]): GammaNavigationItem | null {
    const byId = new Map<string, MenuNode>();
    const index = (list: MenuNode[]): void => {
        for (const n of list) {
            byId.set(n.id, n);
            if (n.children) index(n.children);
        }
    };
    index(nodes);
    const children = favoriteIds
        .map((id) => byId.get(id))
        .filter((n): n is MenuNode => n != null && n.binding != null)
        .map((n) => ({ ...toNavItem(n), id: `fav-${n.id}` }));
    if (!children.length) return null;
    return {
        id: MENU_FAVORITES_NAV_ID,
        title: 'Favorites',
        type: 'collapsable',
        icon: 'heroicons_outline:star',
        children,
    };
}

function toNavItem(n: MenuNode): GammaNavigationItem {
    if (n.binding) {
        return {
            id: `menu-${n.id}`,
            title: n.title,
            type: 'basic',
            icon: n.icon,
            link: `${MENU_ITEM_ROUTE_PREFIX}/${n.id}`,
        };
    }
    return {
        id: `menu-${n.id}`,
        title: n.title,
        type: 'collapsable',
        icon: n.icon,
        children: (n.children ?? []).map(toNavItem),
    };
}
