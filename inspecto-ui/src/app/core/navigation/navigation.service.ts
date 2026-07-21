import { Injectable } from '@angular/core';
import { GammaNavigationItem } from '@gamma/components/navigation';
import { Navigation } from 'app/core/navigation/navigation.types';
import {
    compactNavigation,
    defaultNavigation,
    futuristicNavigation,
    horizontalNavigation,
} from 'app/core/navigation/navigation-data';
import { loadMenuTrees, menuTreeToNav } from 'app/inspecto/menu';
import { cloneDeep } from 'lodash-es';
import { Observable, ReplaySubject, of, tap } from 'rxjs';

/**
 * Keep only the first item per top-level id. Guards the sidebar against duplicate track keys (Angular
 * NG0955 — "duplicated keys for a given collection"), which render a nav group twice; e.g. a custom Menu
 * group colliding with a platform group, or an accidental duplicate entry in {@link defaultNavigation}.
 */
function dedupeById(items: GammaNavigationItem[]): GammaNavigationItem[] {
    const seen = new Set<string>();
    return items.filter((item) => (seen.has(item.id) ? false : (seen.add(item.id), true)));
}

@Injectable({ providedIn: 'root' })
export class NavigationService {
    private _navigation: ReplaySubject<Navigation> =
        new ReplaySubject<Navigation>(1);

    // -----------------------------------------------------------------------------------------------------
    // @ Accessors
    // -----------------------------------------------------------------------------------------------------

    /**
     * Getter for navigation
     */
    get navigation$(): Observable<Navigation> {
        return this._navigation.asObservable();
    }

    // -----------------------------------------------------------------------------------------------------
    // @ Public methods
    // -----------------------------------------------------------------------------------------------------

    /**
     * Get all navigation data. The shell navigation is built entirely client-side — the platform groups
     * are the static {@link defaultNavigation} (compact/futuristic/horizontal reuse its children), and the
     * operator's per-space Menu Builder tree is prepended as top-level custom groups, read fresh on every
     * call so a menu edit refreshes the sidebar on the next fetch. (Formerly served over the Fuse
     * `api/common/navigation` mock; that layer was removed in the M4 shell re-plumb.)
     */
    get(): Observable<Navigation> {
        return of(this._build()).pipe(
            tap((navigation) => {
                this._navigation.next(navigation);
            })
        );
    }

    private _build(): Navigation {
        const _default = cloneDeep(defaultNavigation);
        const _compact = cloneDeep(compactNavigation);
        const _futuristic = cloneDeep(futuristicNavigation);
        const _horizontal = cloneDeep(horizontalNavigation);

        // Fill the compact / futuristic / horizontal variants' children from the default navigation.
        for (const variant of [_compact, _futuristic, _horizontal]) {
            variant.forEach((item) => {
                const match = _default.find((d) => d.id === item.id);
                if (match) {
                    item.children = cloneDeep(match.children);
                }
            });
        }

        // Merge the user's per-space Menu tree (Menu Builder) as top-level siblings of the platform
        // groups, prepended above the custom-menus divider. Read fresh so a re-fetch after an edit
        // refreshes the sidebar.
        const space =
            (typeof localStorage !== 'undefined' && localStorage.getItem('inspecto.currentSpace')) ||
            'default';
        const tree = loadMenuTrees()[space];
        const custom = tree ? menuTreeToNav(tree.nodes) : [];
        const withCustom = (nav: GammaNavigationItem[]): GammaNavigationItem[] =>
            dedupeById(custom.length ? [...cloneDeep(custom), ...nav] : nav);

        return {
            compact: withCustom(_compact),
            default: withCustom(_default),
            futuristic: withCustom(_futuristic),
            horizontal: withCustom(_horizontal),
        };
    }
}
