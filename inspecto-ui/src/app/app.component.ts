// import { Component } from '@angular/core';
// import { RouterOutlet } from '@angular/router';

// @Component({
//     selector: 'app-root',
//     templateUrl: './app.component.html',
//     styleUrls: ['./app.component.scss'],
//     imports: [RouterOutlet],
// })
// export class AppComponent {
//     /**
//      * Constructor
//      */
//     constructor() {}
// }


import {
    ChangeDetectorRef,
    Component,
    DestroyRef,
    OnInit,
    effect,
    inject,
} from '@angular/core';
import { DOCUMENT, Location } from '@angular/common';
import { Platform } from '@angular/cdk/platform';
import { HttpClient } from '@angular/common/http';
import {
    ActivatedRoute,
    NavigationEnd,
    NavigationExtras,
    NavigationStart,
    Router,
    RouterModule,
    UrlTree,
} from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter, startWith } from 'rxjs/operators';

import { GammaNavigationService } from '@gamma/components/navigation/navigation.service';
import { AppComponentService } from './app-component.service';
import { Title } from '@angular/platform-browser';
import moment from 'moment';
import { defaultNavigation } from './mock-api/common/navigation/data';
import { User } from './modules/auth/user.types';
import { AuthService } from './modules/auth/auth-service';

import { LoaderService } from './modules/commons/loader/loader.service';
import { SecurityPrincipal } from './modules/commons/security-principal';
import { PageManager } from './modules/commons/page.manager';
import { AppProperties } from './modules/commons/app.properties';
import { PermissionUtils } from './modules/auth/user-permission/permission-utils';
import { AppUtils } from './modules/commons/app-utils';
import { environment } from 'environments/environment';

@Component({
    selector: 'app-root',
    templateUrl: './app.component.html',
    styleUrls: ['./app.component.scss'],
    standalone: true,
    imports: [RouterModule],
})
export class AppComponent implements OnInit {

    // --- Injected services via inject() (Angular 14+, idiomatic in Angular 21) ---
    private readonly document = inject(DOCUMENT);
    private readonly cdr = inject(ChangeDetectorRef);
    private readonly destroyRef = inject(DestroyRef);              // replaces OnDestroy + Subject pattern
    private readonly platform = inject(Platform);
    private readonly http = inject(HttpClient);
    private readonly authService = inject(AuthService);
    private readonly appComponentService = inject(AppComponentService);
    private readonly loaderService = inject(LoaderService);
    private readonly location = inject(Location);
    private readonly router = inject(Router);
    private readonly activatedRoute = inject(ActivatedRoute);
    private readonly title = inject(Title);
    private readonly securityPrincipal = inject(SecurityPrincipal);
    private readonly pageManager = inject(PageManager);
    private readonly props = inject(AppProperties);
    private readonly permissionUtils = inject(PermissionUtils);
    private readonly gammaNavigationService = inject(GammaNavigationService);

    // --- Component state ---
    showLoader = false;
    gammaConfig: any;
    navigation: any;
    loggedUserName = '';
    loggedUserPassword = '';
    sessionTimedOut = false;
    user = new User();
    errorMessage = '';
    pagesName: any;

    constructor() {
        this.navigation = defaultNavigation;

        // Register navigation
        this.gammaNavigationService.storeNavigation('basics', this.navigation);
        console.log(this.navigation, 'navigation');

        // Add is-mobile class if on a mobile platform
        if (this.platform.ANDROID || this.platform.IOS) {
            this.document.body.classList.add('is-mobile');
        }
        effect(() => {
            this.showLoader = this.loaderService.status();
            this.cdr.markForCheck(); // preferred over detectChanges() with signals
        });
        effect(() => {
            if (this.securityPrincipal.onPrincipalLoad().length > 0) {
                this.loadUserProfile();
            }
        });

        effect(() => {
            if (this.securityPrincipal.onProfileLoad().length > 0) {
                const _roleNames = this.securityPrincipal.getRoleNames();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    ngOnInit(): void {
        this.patchRouterNavigate();
        this.interceptRouterChanges();

        // Loader subscription — automatically unsubscribed when component is destroyed
        // this.loaderService.status
        //     .pipe(takeUntilDestroyed(this.destroyRef))
        //     .subscribe((val: boolean) => {
        //         this.showLoader = val;
        //         this.cdr.detectChanges();
        //     });
        // // Session / principal load
        // this.securityPrincipal.onPrincipalLoad
        //     .pipe(takeUntilDestroyed(this.destroyRef))
        //     .subscribe((data) => {
        //         if (data?.length > 0) {
        //             this.loadUserProfile();
        //         }
        //     });

        if (this.securityPrincipal.isLoggedIn()) {
            this.loadUserProfile();
        }

        // Profile load
        // this.securityPrincipal.onProfileLoad
        //     .pipe(takeUntilDestroyed(this.destroyRef))
        //     .subscribe((data) => {
        //         if (data?.length > 0) {
        //             // Role-based nav filtering — extend as needed
        //             const _roleNames = this.securityPrincipal.getRoleNames();
        //         }
        //     });
    }

    // -------------------------------------------------------------------------
    // Navigation helpers
    // -------------------------------------------------------------------------

    applyAppMenuItems(parentItem: any, children: any[]): void {
        const navId = parentItem['id'];
        children.forEach((child) => {
            const subChildren = child['children'];
            if (subChildren?.length > 0) {
                this.applyAppMenuItems(child, subChildren);
            } else {
                const url = child['url'];
                if (this.permissionUtils.isRoutePermitted(url)) {
                    // this.gammaNavigationService.addNavigationItem(child, navId);
                }
            }
        });
    }

    loadUserProfile(): void {
        this.appComponentService
            .getUserDetails()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (data) => this.securityPrincipal.loadUserProfile(data),
                error: (err) => console.error(err),
            });
    }

    // -------------------------------------------------------------------------
    // Router interception
    // -------------------------------------------------------------------------

    interceptRouterChanges(): void {
        this.router.events
            .pipe(
                filter((e): e is NavigationEnd => e instanceof NavigationEnd),
                startWith(this.router),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe((event) => {
                const routeUrl = event['url'];
                const activeBaseURL = routeUrl?.split('?')[0];

                if (activeBaseURL?.startsWith('/apps/') && this.securityPrincipal.isLoggedIn()) {
                    const principal = this.securityPrincipal.getPrincipalName();
                    AppUtils.getObservableTitle(this.activatedRoute, true)
                        .pipe(takeUntilDestroyed(this.destroyRef))
                        .subscribe((data) => {
                            this.title.setTitle(data);
                            this.saveRouterActionEvent(routeUrl, activeBaseURL, principal, data);
                        });
                }
            });
    }

    private saveRouterActionEvent(
        routeUrl: string,
        activeBaseURL: string,
        principal: string,
        pageTitle: string,
    ): void {
        pageTitle = pageTitle.replace(AppUtils.APP_PREFIX, '');
        const timeStamp = moment().valueOf();

        let baseUrl = this.props.appBaseContext;
        if (baseUrl.endsWith('/')) baseUrl = baseUrl.slice(0, -1);

        const pageUrl = baseUrl + routeUrl;
        const event = {
            appName: environment.appName,
            pageRoute: activeBaseURL,
            pageUrl,
            principal,
            pageTitle,
            requestTimeInMillis: timeStamp,
            dateTime: this.dateFormatter(timeStamp, 'YYYY-MM-DD HH:mm:ss'),
        };

        this.appComponentService
            .saveRouterActionEvent(event)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({ error: (err) => console.error(err) });
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    dateFormatter(timeInMillis: number, formatter: string): string {
        return moment(new Date(timeInMillis)).format(formatter);
    }

    toggleSidebarOpen(_key: string): void {
        // this.gammaSidebarService.getSidebar(key).toggleOpen();
    }

    /**
     * Patch router.navigate to open a new tab on Ctrl/Cmd+click.
     * Uses arrow function to preserve `this` without .bind().
     */
    patchRouterNavigate(): void {
        const originalNavigate = this.router.navigate.bind(this.router);

        this.router.navigate = (
            commands: any[],
            extras?: NavigationExtras,
        ): Promise<boolean> => {
            const tree: UrlTree = this.router.createUrlTree(commands, extras);
            const url = this.router.serializeUrl(tree);
            const basePath = environment.basePath;
            const mainUrl = basePath === '/' ? url : basePath + url;

            if (window.event && (window.event as MouseEvent).ctrlKey) {
                window.open(mainUrl, '_blank');
                return Promise.resolve(false);
            }

            return originalNavigate(commands, extras);
        };
    }
}