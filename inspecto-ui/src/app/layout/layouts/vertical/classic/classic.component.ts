import { Component, OnDestroy, OnInit, ViewEncapsulation, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';
import { GammaLoadingBarComponent } from '@gamma/components/loading-bar';
import {
    GammaNavigationItem,
    GammaNavigationService,
    GammaVerticalNavigationComponent,
} from '@gamma/components/navigation';
import { flattenNavForSearch } from 'app/layout/layouts/vertical/classic/nav-search.util';
import { GammaMediaWatcherService } from '@gamma/services/media-watcher';
import { NavigationService } from 'app/core/navigation/navigation.service';
import { Navigation } from 'app/core/navigation/navigation.types';
// import { LanguagesComponent } from 'app/layout/common/languages/languages.component';
// import { QuickChatComponent } from 'app/layout/common/quick-chat/quick-chat.component';
// import { ShortcutsComponent } from 'app/layout/common/shortcuts/shortcuts.component';
import { LensSwitcherComponent } from 'app/layout/common/lens-switcher/lens-switcher.component';
import { NotificationBellComponent } from 'app/layout/common/notifications/notifications.component';
import { SearchComponent } from 'app/layout/common/search/search.component';
import { SpaceSwitcherComponent } from 'app/layout/common/space-switcher/space-switcher.component';
import { UserComponent } from 'app/layout/common/user/user.component';
import { BrandingService } from 'app/inspecto/api';
import { Subject, takeUntil } from 'rxjs';

@Component({
    standalone: true,
    selector: 'classic-layout',
    templateUrl: './classic.component.html',
    encapsulation: ViewEncapsulation.None,
    imports: [
        GammaLoadingBarComponent,
        GammaVerticalNavigationComponent,
        MatButtonModule,
        MatIconModule,
        LensSwitcherComponent,
        NotificationBellComponent,
        SearchComponent,
        SpaceSwitcherComponent,
        UserComponent,
        RouterOutlet,
        // LanguagesComponent,
        // ShortcutsComponent,
        // QuickChatComponent,
    ],
})
export class ClassicLayoutComponent implements OnInit, OnDestroy {
    isScreenSmall: boolean;
    navigation: Navigation;
    /** Navigation actually rendered in the sidebar — the full tree, or flattened search results. */
    displayedNavigation: GammaNavigationItem[] = [];
    /** Sidebar menu search query (client-side filter of the nav tree). */
    navSearchQuery = '';
    private _unsubscribeAll: Subject<any> = new Subject<any>();
    /** Active-space branding (logo / caption / footer), falling back to the shipped defaults. */
    protected readonly branding = inject(BrandingService);


    /**
     * Constructor
     */
    constructor(
        private _activatedRoute: ActivatedRoute,
        private _router: Router,
        private _navigationService: NavigationService,
        private _gammaMediaWatcherService: GammaMediaWatcherService,
        private _gammaNavigationService: GammaNavigationService
    ) {}

    // -----------------------------------------------------------------------------------------------------
    // @ Accessors
    // -----------------------------------------------------------------------------------------------------

    /**
     * Getter for current year
     */
    get currentYear(): number {
        return new Date().getFullYear();
    }

    // -----------------------------------------------------------------------------------------------------
    // @ Lifecycle hooks
    // -----------------------------------------------------------------------------------------------------

    /**
     * On init
     */
    ngOnInit(): void {
        // Subscribe to navigation data
        this._navigationService.navigation$
            .pipe(takeUntil(this._unsubscribeAll))
            .subscribe((navigation: Navigation) => {
                this.navigation = navigation;
                this._applyNavSearch();
            });

        // Subscribe to media changes
        this._gammaMediaWatcherService.onMediaChange$
            .pipe(takeUntil(this._unsubscribeAll))
            .subscribe(({ matchingAliases }) => {
                // Check if the screen is small
                this.isScreenSmall = !matchingAliases.includes('md');
            });
    }

    /**
     * On destroy
     */
    ngOnDestroy(): void {
        // Unsubscribe from all subscriptions
        this._unsubscribeAll.next(null);
        this._unsubscribeAll.complete();
    }

    // -----------------------------------------------------------------------------------------------------
    // @ Public methods
    // -----------------------------------------------------------------------------------------------------

    /**
     * Toggle navigation
     *
     * @param name
     */
    toggleNavigation(name: string): void {
        // Get the navigation
        const navigation =
            this._gammaNavigationService.getComponent<GammaVerticalNavigationComponent>(
                name
            );

        if (navigation) {
            // Toggle the opened status
            navigation.toggle();
        }
    }

    /**
     * Whether the menu search is actively filtering (non-empty query).
     */
    get navSearchActive(): boolean {
        return this.navSearchQuery.trim().length > 0;
    }

    /**
     * Handle a keystroke in the menu search box — re-filter the navigation live.
     *
     * @param query
     */
    onNavSearch(query: string): void {
        this.navSearchQuery = query;
        this._applyNavSearch();
    }

    /**
     * Clear the menu search and restore the full navigation tree.
     */
    clearNavSearch(): void {
        this.navSearchQuery = '';
        this._applyNavSearch();
    }

    /**
     * Navigate to the first search result (Enter key), then clear the search.
     */
    goToFirstResult(): void {
        const first = this.displayedNavigation[0];

        if (this.navSearchActive && first?.link) {
            this._router.navigateByUrl(first.link);
            this.clearNavSearch();
        }
    }

    /**
     * Recompute the rendered navigation: flattened search results while filtering, otherwise the
     * full tree. A new array reference is assigned so the OnPush vertical-navigation re-renders.
     *
     * @private
     */
    private _applyNavSearch(): void {
        const full = this.navigation?.default ?? [];
        this.displayedNavigation = this.navSearchActive
            ? flattenNavForSearch(full, this.navSearchQuery)
            : full;
    }
}