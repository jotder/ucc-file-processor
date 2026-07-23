import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { NavigationService } from 'app/core/navigation/navigation.service';
import { SpacesService } from 'app/inspecto/api';
import { NavMenusService } from './menu-api';
import { MenuService } from './menu.service';
import { emptyTree, MenuTree } from './menu-types';

/** A writable stand-in for SpacesService's `currentSpaceId` signal so we can switch spaces in a test. */
const spaceId = signal<string | null>('s1');

/** A tiny in-memory stand-in for the /nav/menus backend, keyed by space. It survives
 *  `resetTestingModule` (module scope) so "persists across instances" exercises the server round-trip. */
const backend = new Map<string, MenuTree>();
const navApi = {
    get: () => of(backend.get(spaceId() ?? 'default') ?? emptyTree(spaceId() ?? 'default')),
    put: vi.fn((tree: MenuTree) => {
        const saved: MenuTree = { space: spaceId() ?? 'default', version: 1, nodes: tree.nodes };
        backend.set(spaceId() ?? 'default', saved);
        return of(saved);
    }),
};

function configure(): void {
    TestBed.configureTestingModule({
        providers: [
            { provide: SpacesService, useValue: { currentSpaceId: spaceId } },
            { provide: NavMenusService, useValue: navApi },
            { provide: NavigationService, useValue: { get: () => of(null) } },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
}

describe('MenuService', () => {
    beforeEach(() => {
        localStorage.clear();
        backend.clear();
        spaceId.set('s1');
        navApi.put.mockClear();
        configure();
    });

    it('starts empty and persists a mutation to the server across service instances', () => {
        const svc = TestBed.inject(MenuService);
        expect(svc.nodes()).toEqual([]);
        const id = svc.mutate((s) => s.addMenu('Revenue'));
        expect(svc.nodes().map((n) => n.title)).toEqual(['Revenue']);
        expect(navApi.put).toHaveBeenCalled();

        // a fresh DI scope hydrates from the server (not localStorage)
        TestBed.resetTestingModule();
        configure();
        const svc2 = TestBed.inject(MenuService);
        expect(svc2.nodes().map((n) => n.title)).toEqual(['Revenue']);
        expect(svc2.tree().nodes[0].id).toBe(id);
    });

    it('scopes the tree per space', () => {
        const svc = TestBed.inject(MenuService);
        svc.mutate((s) => s.addMenu('Revenue'));

        spaceId.set('s2');
        expect(svc.nodes()).toEqual([]); // s2 has its own (empty) tree
        svc.mutate((s) => s.addMenu('FMS'));
        expect(svc.nodes().map((n) => n.title)).toEqual(['FMS']);

        spaceId.set('s1');
        expect(svc.nodes().map((n) => n.title)).toEqual(['Revenue']);
    });

    it('toggles a personal favorite, reflects it in isFavorite/favoriteIds, and scopes it per space', () => {
        const svc = TestBed.inject(MenuService);
        expect(svc.favoriteIds()).toEqual([]);

        svc.toggleFavorite('leaf-1');
        expect(svc.isFavorite('leaf-1')).toBe(true);
        expect(svc.favoriteIds()).toEqual(['leaf-1']);

        spaceId.set('s2');
        expect(svc.isFavorite('leaf-1')).toBe(false); // favorites are per space
        spaceId.set('s1');

        svc.toggleFavorite('leaf-1'); // off again
        expect(svc.isFavorite('leaf-1')).toBe(false);
    });

    it('never writes favorites to the server, and they survive a fresh instance via the local mirror', () => {
        const svc = TestBed.inject(MenuService);
        svc.toggleFavorite('leaf-9');
        expect(navApi.put).not.toHaveBeenCalled(); // favorites are client-local, not a tree mutation

        TestBed.resetTestingModule();
        configure();
        const svc2 = TestBed.inject(MenuService);
        expect(svc2.isFavorite('leaf-9')).toBe(true);
    });
});
