import { Component, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';
import { GammaLoadingBarComponent } from '@gamma/components/loading-bar';
import {
    GammaNavigationService,
    GammaVerticalNavigationComponent,
} from '@gamma/components/navigation';
import { GammaMediaWatcherService } from '@gamma/services/media-watcher';
import { NavigationService } from 'app/core/navigation/navigation.service';
import { Navigation } from 'app/core/navigation/navigation.types';
// import { LanguagesComponent } from 'app/layout/common/languages/languages.component';
// import { QuickChatComponent } from 'app/layout/common/quick-chat/quick-chat.component';
// import { ShortcutsComponent } from 'app/layout/common/shortcuts/shortcuts.component';
import { NotificationBellComponent } from 'app/layout/common/notifications/notifications.component';
import { SearchComponent } from 'app/layout/common/search/search.component';
import { SpaceSwitcherComponent } from 'app/layout/common/space-switcher/space-switcher.component';
import { UserComponent } from 'app/layout/common/user/user.component';
import { environment } from 'environments/environment';
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
    private _unsubscribeAll: Subject<any> = new Subject<any>();
    logoUrl = environment.appLogo;
    footerText = environment.footerText;


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
}