import { computed, inject, Injectable, signal } from '@angular/core';
import { ToastrService } from 'ngx-toastr';
import { NavigationService } from 'app/core/navigation/navigation.service';
import { apiErrorMessage, SpacesService } from 'app/inspecto/api';
import { NavMenusService } from './menu-api';
import { loadMenuFavorites, saveMenuFavorites } from './menu-favorites';
import { loadMenuTrees, saveMenuTrees } from './menu-persist';
import { MenuStore } from './menu-store';
import { emptyTree, MenuNode, MenuTree } from './menu-types';

const DEFAULT_SPACE = 'default';

/**
 * Holds the per-Space Menu tree as a signal. The server (`GET/PUT /nav/menus`, {@link NavMenusService})
 * is the source of truth; the `localStorage` mirror ({@link loadMenuTrees}) gives an instant first
 * paint, feeds the synchronous sidebar merge (the gamma nav mock reads it directly), and keeps the
 * builder working offline. On construction the active Space is hydrated from the server (switching
 * Space reloads the app, so once is enough) and mutations write through — optimistic locally + a PUT.
 */
@Injectable({ providedIn: 'root' })
export class MenuService {
    private readonly spaces = inject(SpacesService);
    private readonly api = inject(NavMenusService);
    private readonly navigation = inject(NavigationService);
    private readonly toastr = inject(ToastrService);
    private readonly store = signal<Record<string, MenuTree>>(this.load());
    /** Personal favorites overlay (per Space), client-local — never sent to the server. */
    private readonly favorites = signal<Record<string, string[]>>(loadMenuFavorites());

    constructor() {
        const space = this.spaceKey();
        this.api.get().subscribe({
            next: (tree) => {
                const changed = JSON.stringify(this.store()[space]?.nodes ?? []) !== JSON.stringify(tree.nodes);
                this.persist({ ...this.store(), [space]: tree });
                // The sidebar was built from the mirror; refresh it when the server tree differs (e.g.
                // menus authored in another browser/session that this device's mirror hadn't seen).
                if (changed) this.navigation.get().subscribe();
            },
            error: () => {
                /* offline or read-only control plane: keep the mirror as-is */
            },
        });
    }

    private spaceKey(): string {
        return this.spaces.currentSpaceId() ?? DEFAULT_SPACE;
    }

    /** The active Space's Menu tree (empty when nothing saved yet). */
    readonly tree = computed<MenuTree>(() => this.store()[this.spaceKey()] ?? emptyTree(this.spaceKey()));
    readonly nodes = computed<MenuNode[]>(() => this.tree().nodes);

    /** Find a node anywhere in the active Space's tree (e.g. the dynamic host resolving `/w/:nodeId`). */
    find(id: string): MenuNode | undefined {
        return new MenuStore(this.tree()).find(id);
    }

    /** This device's favorited leaf ids for the active Space (order = the order they were starred). */
    readonly favoriteIds = computed<string[]>(() => this.favorites()[this.spaceKey()] ?? []);

    /** Whether a leaf is in this device's personal Favorites for the active Space. */
    isFavorite(id: string): boolean {
        return this.favoriteIds().includes(id);
    }

    /** Toggle a leaf's personal-favorite state — client-local + per Space, persisted to the mirror only
     *  (favorites are personal; the shared tree write-through in {@link mutate} does not touch them). */
    toggleFavorite(id: string): void {
        const space = this.spaceKey();
        const current = this.favorites()[space] ?? [];
        const next = current.includes(id) ? current.filter((f) => f !== id) : [...current, id];
        const store = { ...this.favorites(), [space]: next };
        this.favorites.set(store);
        saveMenuFavorites(store);
    }

    /** Apply pure ops against the active Space's tree, persist (optimistic + mirror), then write through
     *  to the server. Returns whatever `fn` returns. A failed save toasts; the next load reconciles. */
    mutate<T>(fn: (s: MenuStore) => T): T {
        const s = new MenuStore(this.tree());
        const result = fn(s);
        const next = s.snapshot();
        const space = this.spaceKey();
        this.persist({ ...this.store(), [space]: next });
        this.api.put(next).subscribe({
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not save the menu changes to the server.')),
        });
        return result;
    }

    private load(): Record<string, MenuTree> {
        return loadMenuTrees();
    }
    private persist(next: Record<string, MenuTree>): void {
        this.store.set(next);
        saveMenuTrees(next);
    }
}
