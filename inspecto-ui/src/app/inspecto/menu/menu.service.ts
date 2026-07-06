import { computed, inject, Injectable, signal } from '@angular/core';
import { SpacesService } from 'app/inspecto/api';
import { MenuStore } from './menu-store';
import { emptyTree, MenuNode, MenuTree } from './menu-types';

const KEY = 'inspecto.menuTree.v1';
const DEFAULT_SPACE = 'default';

/**
 * Holds the per-Space Menu tree as a signal, restored from `localStorage` synchronously (mirrors
 * {@link LensService}/{@link SqlHistoryService}). Mutations run through a pure {@link MenuStore} then
 * persist. **Mock-first (plan D1):** the persisted shape is exactly what a future `GET/PUT /nav/menus`
 * endpoint will carry, so the backend is an additive drop-in. See docs/superpower/menu-builder-plan.md.
 */
@Injectable({ providedIn: 'root' })
export class MenuService {
    private readonly spaces = inject(SpacesService);
    private readonly store = signal<Record<string, MenuTree>>(this.load());

    private spaceKey(): string {
        return this.spaces.currentSpaceId() ?? DEFAULT_SPACE;
    }

    /** The active Space's Menu tree (empty when nothing saved yet). */
    readonly tree = computed<MenuTree>(() => this.store()[this.spaceKey()] ?? emptyTree(this.spaceKey()));
    readonly nodes = computed<MenuNode[]>(() => this.tree().nodes);

    /** Apply pure ops against the active Space's tree and persist. Returns whatever `fn` returns. */
    mutate<T>(fn: (s: MenuStore) => T): T {
        const s = new MenuStore(this.tree());
        const result = fn(s);
        this.persist({ ...this.store(), [this.spaceKey()]: s.snapshot() });
        return result;
    }

    private load(): Record<string, MenuTree> {
        try {
            const raw = localStorage.getItem(KEY);
            return raw ? (JSON.parse(raw) as Record<string, MenuTree>) : {};
        } catch {
            return {};
        }
    }
    private persist(next: Record<string, MenuTree>): void {
        this.store.set(next);
        try {
            localStorage.setItem(KEY, JSON.stringify(next));
        } catch {
            /* quota exceeded / storage disabled — keep the in-memory copy */
        }
    }
}
