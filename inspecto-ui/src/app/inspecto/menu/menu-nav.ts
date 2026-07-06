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
