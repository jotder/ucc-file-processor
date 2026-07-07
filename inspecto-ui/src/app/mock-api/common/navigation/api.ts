import { Injectable } from '@angular/core';
import { GammaNavigationItem } from '@gamma/components/navigation';
import { GammaMockApiService } from '@gamma/lib/mock-api';
import { loadMenuTrees, menuTreeToNav } from 'app/inspecto/menu';
import {
    compactNavigation,
    defaultNavigation,
    futuristicNavigation,
    horizontalNavigation,
} from 'app/mock-api/common/navigation/data';
import { cloneDeep } from 'lodash-es';

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
export class NavigationMockApi {
    private readonly _compactNavigation: GammaNavigationItem[] =
        compactNavigation;
    private readonly _defaultNavigation: GammaNavigationItem[] =
        defaultNavigation;
    private readonly _futuristicNavigation: GammaNavigationItem[] =
        futuristicNavigation;
    private readonly _horizontalNavigation: GammaNavigationItem[] =
        horizontalNavigation;

    /**
     * Constructor
     */
    constructor(private _gammaMockApiService: GammaMockApiService) {
        // Register Mock API handlers
        this.registerHandlers();
    }

    // -----------------------------------------------------------------------------------------------------
    // @ Public methods
    // -----------------------------------------------------------------------------------------------------

    /**
     * Register Mock API handlers
     */
    registerHandlers(): void {
        // -----------------------------------------------------------------------------------------------------
        // @ Navigation - GET
        // -----------------------------------------------------------------------------------------------------
        this._gammaMockApiService.onGet('api/common/navigation').reply(() => {
            // Fill compact navigation children using the default navigation
            this._compactNavigation.forEach((compactNavItem) => {
                this._defaultNavigation.forEach((defaultNavItem) => {
                    if (defaultNavItem.id === compactNavItem.id) {
                        compactNavItem.children = cloneDeep(
                            defaultNavItem.children
                        );
                    }
                });
            });

            // Fill futuristic navigation children using the default navigation
            this._futuristicNavigation.forEach((futuristicNavItem) => {
                this._defaultNavigation.forEach((defaultNavItem) => {
                    if (defaultNavItem.id === futuristicNavItem.id) {
                        futuristicNavItem.children = cloneDeep(
                            defaultNavItem.children
                        );
                    }
                });
            });

            // Fill horizontal navigation children using the default navigation
            this._horizontalNavigation.forEach((horizontalNavItem) => {
                this._defaultNavigation.forEach((defaultNavItem) => {
                    if (defaultNavItem.id === horizontalNavItem.id) {
                        horizontalNavItem.children = cloneDeep(
                            defaultNavItem.children
                        );
                    }
                });
            });

            // Merge the user's per-space Menu tree (Menu Builder) as top-level siblings of the platform
            // groups. Read fresh on every fetch, so re-fetching after an edit refreshes the sidebar.
            const space =
                (typeof localStorage !== 'undefined' && localStorage.getItem('inspecto.currentSpace')) ||
                'default';
            const tree = loadMenuTrees()[space];
            const custom = tree ? menuTreeToNav(tree.nodes) : [];
            const withCustom = (nav: GammaNavigationItem[]): GammaNavigationItem[] =>
                dedupeById(custom.length ? [...cloneDeep(custom), ...nav] : nav);

            // Return the response
            return [
                200,
                {
                    compact: withCustom(cloneDeep(this._compactNavigation)),
                    default: withCustom(cloneDeep(this._defaultNavigation)),
                    futuristic: withCustom(cloneDeep(this._futuristicNavigation)),
                    horizontal: withCustom(cloneDeep(this._horizontalNavigation)),
                },
            ];
        });
    }
}
