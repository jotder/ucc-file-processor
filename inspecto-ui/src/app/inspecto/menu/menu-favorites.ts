/**
 * Persistence for **personal** Menu favorites — a client-local overlay (a set of favorited leaf node
 * ids per Space), deliberately separate from the shared per-Space Menu tree: favorites are personal to
 * this device, the tree is shared and server-backed (plan §4.2). A single source for the storage key +
 * shape, shared by the Angular {@link MenuService} and the navigation builder (which surfaces the
 * favorites as a virtual "Favorites" group). Front-end only — never round-tripped to the server.
 */
export const MENU_FAVORITES_KEY = 'inspecto.menuFavorites.v1';

/** All spaces' favorite id sets, keyed by space id. Tolerant of missing / corrupt storage. */
export function loadMenuFavorites(): Record<string, string[]> {
    try {
        const raw = localStorage.getItem(MENU_FAVORITES_KEY);
        return raw ? (JSON.parse(raw) as Record<string, string[]>) : {};
    } catch {
        return {};
    }
}

export function saveMenuFavorites(favorites: Record<string, string[]>): void {
    try {
        localStorage.setItem(MENU_FAVORITES_KEY, JSON.stringify(favorites));
    } catch {
        /* quota exceeded / storage disabled — caller keeps the in-memory copy */
    }
}
