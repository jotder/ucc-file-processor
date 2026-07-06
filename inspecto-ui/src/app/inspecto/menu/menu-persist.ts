import { MenuTree } from './menu-types';

/**
 * Persistence for the Menu tree — a single source for the storage key + shape, shared by the Angular
 * {@link MenuService} and the navigation mock (which merges the tree into the sidebar). Mock-first
 * (plan D1): swapping this for a `GET/PUT /nav/menus` HTTP call later leaves both callers unchanged.
 */
export const MENU_STORAGE_KEY = 'inspecto.menuTree.v1';

/** All spaces' trees, keyed by space id. Tolerant of missing / corrupt storage. */
export function loadMenuTrees(): Record<string, MenuTree> {
    try {
        const raw = localStorage.getItem(MENU_STORAGE_KEY);
        return raw ? (JSON.parse(raw) as Record<string, MenuTree>) : {};
    } catch {
        return {};
    }
}

export function saveMenuTrees(trees: Record<string, MenuTree>): void {
    try {
        localStorage.setItem(MENU_STORAGE_KEY, JSON.stringify(trees));
    } catch {
        /* quota exceeded / storage disabled — caller keeps the in-memory copy */
    }
}
