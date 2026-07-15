import { Component, HostListener, OnDestroy, OnInit, ViewChild, ViewEncapsulation, effect, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
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
import { SearchCommand, SearchComponent } from 'app/layout/common/search/search.component';
import { SpaceSwitcherComponent } from 'app/layout/common/space-switcher/space-switcher.component';
import { UserComponent } from 'app/layout/common/user/user.component';
import { AccessStateService } from 'app/inspecto/access/access-state.service';
import { BrandingService, LensService } from 'app/inspecto/api';
import { ShortcutsHelpDialog } from 'app/inspecto/shortcuts-help.dialog';
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
    /** Lens Access Profiles — filters the sidebar per lens (identity when none saved). */
    private readonly _accessState = inject(AccessStateService);
    private readonly _lens = inject(LensService);
    private readonly _dialog = inject(MatDialog);

    /** The header command palette — focused app-wide by Ctrl/Cmd+K. */
    @ViewChild(SearchComponent) private _search?: SearchComponent;

    /** Action commands offered in the palette. Shell-owned only (no cross-feature coupling): switch
     *  the persona lens. Feature-contextual commands await a command registry (review R3 follow-up). */
    protected readonly paletteCommands: SearchCommand[] = LensService.LENSES.map((l) => ({
        title: `Switch to ${l.label} lens`,
        icon: l.icon,
        group: 'Lens',
        run: () => this._lens.selectLens(l.id),
    }));


    /**
     * Constructor
     */
    constructor(
        private _activatedRoute: ActivatedRoute,
        private _router: Router,
        private _navigationService: NavigationService,
        private _gammaMediaWatcherService: GammaMediaWatcherService,
        private _gammaNavigationService: GammaNavigationService
    ) {
        // Re-filter the sidebar when the lens or the saved Access Profiles change (both signals
        // are read inside _applyNavSearch via AccessStateService.filterNav).
        effect(() => this._applyNavSearch());
    }

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
    // @ Keyboard shortcuts (review R3)
    // -----------------------------------------------------------------------------------------------------

    /**
     * App-wide keyboard entry points: **Ctrl/Cmd+K** opens the command palette; **?** shows the
     * shortcuts help. Both are ignored while the user is typing in a field so they never eat input.
     */
    @HostListener('document:keydown', ['$event'])
    onGlobalKeydown(event: KeyboardEvent): void {
        const key = event.key.toLowerCase();
        if ((event.ctrlKey || event.metaKey) && key === 'k') {
            event.preventDefault();
            this._search?.open();
            return;
        }
        // `?` = Shift+/. Only a bare press (no modifier), and never while editing text.
        if (event.key === '?' && !event.ctrlKey && !event.metaKey && !event.altKey && !this._isEditingTarget(event.target)) {
            event.preventDefault();
            this._dialog.open(ShortcutsHelpDialog, { width: '460px', autoFocus: false });
        }
    }

    /** True when the event originates in a text-editing control (so shortcuts stay out of the way). */
    private _isEditingTarget(target: EventTarget | null): boolean {
        const el = target as HTMLElement | null;
        if (!el) return false;
        const tag = el.tagName;
        return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
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
        const full = this._accessState.filterNav(this.navigation?.default ?? []);
        this.displayedNavigation = this.navSearchActive
            ? flattenNavForSearch(full, this.navSearchQuery)
            : full;
    }
}