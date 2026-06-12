import { Injectable } from '@angular/core';
import { ActivatedRoute, NavigationExtras } from '@angular/router';
import { Observable, of } from 'rxjs';
import { PageManager } from './page.manager';
import { AppProperties } from './app.properties';
import { environment } from 'environments/environment';

@Injectable({
    providedIn: 'root',
})
export class AppUtils {
    static readonly APP_PREFIX = ' | Skybase';
    static readonly DEFAULT_TITLE = 'App';

    static getBaseRoute(activatedRoute: ActivatedRoute): ActivatedRoute {
        return activatedRoute.firstChild
            ? AppUtils.getBaseRoute(activatedRoute.firstChild)
            : activatedRoute;
    }

    static getObservableTitle(activatedRoute: ActivatedRoute, signature: boolean): Observable<string> {
        const childRoute = AppUtils.getBaseRoute(activatedRoute);
        let pageTitle: string = childRoute.snapshot.data['title'] ?? AppUtils.DEFAULT_TITLE;

        if (signature && !pageTitle.endsWith(AppUtils.APP_PREFIX)) {
            pageTitle += AppUtils.APP_PREFIX;
        }

        return of(pageTitle);
    }

    static redirectToAuthServer(props: AppProperties, pageManager: PageManager): void {
        const params = new URLSearchParams({
            client_id: props.appClientId,
            response_type: 'code',
            scope: props.appScope,
            redirect_uri: props.appRedirectUri,
        });

        const navigateUrl = `${environment.authServerUrl}${environment.authVersion}/authorize?${params}`;
        pageManager.redirectPath = window.location.href;
        window.location.href = navigateUrl;
    }

    static titleCaseWord(word: string): string {
        if (!word) return word;
        return word.charAt(0).toUpperCase() + word.slice(1);
    }

    static getNavigationExtrasFromPath(routePath: string): NavigationExtras {
        const queryString = routePath.substring(routePath.indexOf('?') + 1);
        const queryParams: Record<string, string> = {};
        new URLSearchParams(queryString).forEach((value, key) => {
            queryParams[key] = value;
        });
        return { queryParams };
    }
}